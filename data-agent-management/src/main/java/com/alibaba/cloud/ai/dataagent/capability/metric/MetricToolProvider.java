/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.capability.metric;

import com.alibaba.cloud.ai.dataagent.agentscope.dto.AgentRequest;
import com.alibaba.cloud.ai.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.alibaba.cloud.ai.dataagent.observability.AnswerTraceExplainStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@Qualifier("metricTool")
@RequiredArgsConstructor
class MetricToolProvider {

	private static final String SEARCH_TOOL = "metric.catalog.search";

	private static final String DESCRIBE_TOOL = "metric.catalog.describe";

	private static final String EXECUTE_TOOL = "metric.query.execute";

	private static final String SEARCH_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "query": {
			      "type": "string",
			      "description": "用于检索候选指标的自然语言问题。"
			    },
			    "limit": {
			      "type": "integer",
			      "description": "返回候选指标数量上限，默认 5。"
			    }
			  },
			  "required": ["query"]
			}
			""";

	private static final String DESCRIBE_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "operationId": {
			      "type": "string",
			      "description": "指标接口 operationId，推荐使用 search 返回的 apiId/operationId。"
			    },
			    "metricCode": {
			      "type": "string",
			      "description": "兼容旧调用方式的指标编码。"
			    }
			  },
			  "required": []
			}
			""";

	private static final String EXECUTE_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "operationId": {
			      "type": "string",
			      "description": "要执行的指标接口 operationId。"
			    },
			    "metricCode": {
			      "type": "string",
			      "description": "兼容旧调用方式的指标编码。"
			    },
			    "query": {
			      "type": "string",
			      "description": "原始自然语言问题，用于补参和缺参澄清。"
			    },
			    "arguments": {
			      "type": "object",
			      "description": "接口参数键值对，键名应与接口参数名或 body 字段名一致。"
			    },
			    "timeRange": {
			      "type": "object",
			      "properties": {
			        "start": { "type": "string" },
			        "end": { "type": "string" },
			        "granularity": { "type": "string" },
			        "timezone": { "type": "string" }
			      }
			    },
			    "groupBy": {
			      "type": "array",
			      "items": { "type": "string" }
			    },
			    "filters": {
			      "type": "array",
			      "items": { "type": "object" }
			    },
			    "orderBy": {
			      "type": "array",
			      "items": { "type": "object" }
			    },
			    "limit": {
			      "type": "integer"
			    },
			    "format": {
			      "type": "string"
			    }
			  },
			  "required": []
			}
			""";

	private static final int DEFAULT_SEARCH_LIMIT = 5;

	private final MetricRetrievalService metricRetrievalService;

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final MetricQueryExecutionService metricQueryExecutionService;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final ObjectMapper objectMapper;

	public Map<String, ToolCallback> getToolCallbacks() {
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		callbacks.put(SEARCH_TOOL, new MetricSearchToolCallback());
		callbacks.put(DESCRIBE_TOOL, new MetricDescribeToolCallback());
		callbacks.put(EXECUTE_TOOL, new MetricExecuteToolCallback());
		return Map.copyOf(callbacks);
	}

	private String pickIdentifier(String operationId, String metricCode) {
		if (StringUtils.hasText(operationId)) {
			return operationId.trim();
		}
		return StringUtils.hasText(metricCode) ? metricCode.trim() : "";
	}

	private final class MetricSearchToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(SEARCH_TOOL)
			.description("根据自然语言问题检索候选指标接口，结果按语义相似度排序。")
			.inputSchema(SEARCH_SCHEMA)
			.build();

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			try {
				ObjectNode input = StringUtils.hasText(toolInput) ? (ObjectNode) objectMapper.readTree(toolInput)
						: objectMapper.createObjectNode();
				String query = input.path("query").asText();
				int limit = input.path("limit").asInt(DEFAULT_SEARCH_LIMIT);
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				String agentId = agentRequest == null ? null : agentRequest.getAgentId();
				MetricSearchResult result = metricRetrievalService
					.search(new MetricSearchCommand(query, agentId, limit));
				List<String> apiIds = result.candidates()
					.stream()
					.map(MetricSearchResult.Candidate::operationId)
					.toList();
				answerTraceExplainStore.recordMetricCatalogSearch(agentRequest, query,
						result.summary(), apiIds);
				return objectMapper.writeValueAsString(result);
			}
			catch (Exception ex) {
				log.warn("Metric catalog search failed. toolInputLength={}", inputLength(toolInput), ex);
				throw new IllegalStateException("指标目录检索失败：" + ex.getMessage(), ex);
			}
		}

	}

	private final class MetricDescribeToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(DESCRIBE_TOOL)
			.description("返回某个指标接口的完整契约定义，含参数名、类型、是否必填、枚举值、默认值。")
			.inputSchema(DESCRIBE_SCHEMA)
			.build();

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			try {
				ObjectNode input = StringUtils.hasText(toolInput) ? (ObjectNode) objectMapper.readTree(toolInput)
						: objectMapper.createObjectNode();
				String identifier = pickIdentifier(input.path("operationId").asText(),
						input.path("metricCode").asText());
				log.info("Metric catalog describe invoked. identifier={}", identifier);
				MetricCatalogEntry definition = metricDefinitionLookup.getOnlineEntry(identifier)
					.orElseThrow(() -> new IllegalArgumentException("未找到指标接口定义：" + identifier));
				return objectMapper.writeValueAsString(definition);
			}
			catch (Exception ex) {
				log.warn("Metric catalog describe failed. toolInputLength={}", inputLength(toolInput), ex);
				throw new IllegalStateException("读取指标接口定义失败：" + ex.getMessage(), ex);
			}
		}

	}

	private final class MetricExecuteToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(EXECUTE_TOOL)
			.description("按接口契约执行指标查询，缺少必填参数时返回澄清信息。")
			.inputSchema(EXECUTE_SCHEMA)
			.build();

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			try {
				MetricQueryRequest request = objectMapper.readValue(toolInput, MetricQueryRequest.class);
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				log.info("Metric query execute invoked. operationId={}, queryLength={}, argumentKeys={}, threadId={}",
						request.getOperationId(), inputLength(request.getQuery()),
						request.getArguments() == null ? List.of() : request.getArguments().keySet(),
						agentRequest != null ? agentRequest.getThreadId() : "N/A");
				MetricQueryResult result = metricQueryExecutionService.execute(request);
				log.info("Metric query execute completed. identifier={}, status={}, summary={}, rowCount={}",
						pickIdentifier(request.getOperationId(), request.getMetricCode()), result.status(),
						result.summary(), result.rows() == null ? 0 : result.rows().size());
				answerTraceExplainStore.recordMetricQueryResult(agentRequest,
						pickIdentifier(request.getOperationId(), request.getMetricCode()), result.summary());
				return objectMapper.writeValueAsString(result);
			}
			catch (Exception ex) {
				log.warn("Metric query execute failed. toolInputLength={}", inputLength(toolInput), ex);
				throw new IllegalStateException("指标查询失败：" + ex.getMessage(), ex);
			}
		}

	}

	private static int inputLength(String input) {
		return input == null ? 0 : input.length();
	}

}
