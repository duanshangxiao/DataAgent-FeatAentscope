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
package com.alibaba.cloud.ai.dataagent.agentscope.tool.datasource;

import com.alibaba.cloud.ai.dataagent.agentscope.dto.AgentRequest;
import com.alibaba.cloud.ai.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.alibaba.cloud.ai.dataagent.agentscope.tool.AgentScopedToolProvider;
import com.alibaba.cloud.ai.dataagent.agentscope.tool.ToolError;
import com.alibaba.cloud.ai.dataagent.agentscope.tool.ToolErrorCode;
import com.alibaba.cloud.ai.dataagent.entity.AgentDatasource;
import com.alibaba.cloud.ai.dataagent.entity.Datasource;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatasourceExplorerToolProvider implements AgentScopedToolProvider {

	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "action": {
			      "type": "string",
			      "enum": [
			        "LIST_TABLES",
			        "FIND_TABLES",
			        "GET_TABLE_SCHEMA",
			        "GET_RELATED_TABLES",
			        "PREVIEW_ROWS",
			        "SEARCH"
			      ],
			      "description": "探索动作。"
			    },
			    "query": {
			      "type": "string",
			      "description": "FIND_TABLES 时必填，用于按关键词查找表。"
			    },
			    "tableName": {
			      "type": "string",
			      "description": "GET_TABLE_SCHEMA、GET_RELATED_TABLES、PREVIEW_ROWS 时必填的目标表名。"
			    },
			    "sql": {
			      "type": "string",
			      "description": "SEARCH 时必填的只读 SQL，仅允许 SELECT/WITH。"
			    },
			    "limit": {
			      "type": "integer",
			      "description": "可选返回上限，默认 20，最大 200。"
			    }
			  },
			  "required": ["action"]
			}
			""";

	private final AgentDatasourceService agentDatasourceService;

	private final DatasourceExplorerService datasourceExplorerService;

	private final ObjectMapper objectMapper;

	@Override
	public Map<String, ToolCallback> getToolCallbacks(String agentId) {
		AgentDatasource agentDatasource = resolveActiveDatasource(agentId);
		if (agentDatasource == null || agentDatasource.getDatasource() == null) {
			return Map.of();
		}
		Datasource datasource = agentDatasource.getDatasource();
		String toolName = "datasource.%s.search".formatted(buildDatasourceSlug(datasource));
		String description = buildDescription(datasource, agentDatasource);
		ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(toolName)
			.description(description)
			.inputSchema(INPUT_SCHEMA)
			.build();
		return Map.of(toolName, new AgentBoundDatasourceExplorerToolCallback(agentId, toolDefinition,
				datasourceExplorerService, objectMapper));
	}

	private AgentDatasource resolveActiveDatasource(String agentId) {
		if (!StringUtils.isNumeric(agentId)) {
			return null;
		}
		try {
			return agentDatasourceService.getCurrentAgentDatasource(Long.valueOf(agentId));
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String buildDatasourceSlug(Datasource datasource) {
		String source = StringUtils.defaultIfBlank(datasource.getName(), "datasource");
		String slug = source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		slug = slug.replaceAll("^_+|_+$", "");
		if (StringUtils.isBlank(slug)) {
			return "datasource_" + datasource.getId();
		}
		return slug;
	}

	private String buildDescription(Datasource datasource, AgentDatasource agentDatasource) {
		List<String> selectedTables = agentDatasource.getSelectTables() == null ? List.of()
				: agentDatasource.getSelectTables();
		String visibleTables = selectedTables.isEmpty() ? "当前未显式选表，将回退到数据源全部可见表" : "当前显式选表 %d 个：%s"
			.formatted(selectedTables.size(), String.join(", ", selectedTables.stream().limit(8).toList()));
		return """
				数据源'%s'（%s）的统一探索工具。
				可用于查看表列表、查找表、查看单表结构、查看关系、按需预览样例数据，以及执行只读 SQL 查询。
				约束说明：
				1. 只能访问当前 Agent 的活动数据源。
				2. SEARCH 仅允许执行只读 SQL，禁止 SELECT * 或 table.*，必须显式列出列名（COUNT(*) 除外）。
				3. 如果只需要定位表，优先使用 LIST_TABLES 或 FIND_TABLES。
				4. 如果需要写 SQL，先获取表结构和关系，再决定是否执行 SEARCH。
				5. PREVIEW_ROWS 不是默认前置动作，只有样例值会实质影响 SQL 写法时才使用。
				6. %s
				""".formatted(datasource.getName(), datasource.getType(), visibleTables);
	}

	private static final class AgentBoundDatasourceExplorerToolCallback implements ToolCallback {

		private final String agentId;

		private final ToolDefinition toolDefinition;

		private final DatasourceExplorerService datasourceExplorerService;

		private final ObjectMapper objectMapper;

		private AgentBoundDatasourceExplorerToolCallback(String agentId, ToolDefinition toolDefinition,
				DatasourceExplorerService datasourceExplorerService, ObjectMapper objectMapper) {
			this.agentId = agentId;
			this.toolDefinition = toolDefinition;
			this.datasourceExplorerService = datasourceExplorerService;
			this.objectMapper = objectMapper;
		}

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
				DatasourceExplorerRequest request = objectMapper.readValue(toolInput, DatasourceExplorerRequest.class);
				validateRequest(request);
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				return objectMapper
					.writeValueAsString(datasourceExplorerService.execute(agentId, request, agentRequest));
			}
			catch (Exception ex) {
				throw new IllegalStateException(
						objectToJson(ToolError.of(ToolErrorCode.EXECUTION_FAILED, "数据源探索工具执行失败：" + ex.getMessage())),
						ex);
			}
		}

		private void validateRequest(DatasourceExplorerRequest request) {
			if (request == null || request.getAction() == null) {
				throw new IllegalArgumentException(
						objectToJson(ToolError.of(ToolErrorCode.INVALID_INPUT, "数据源探索工具需要 action 参数")));
			}
			switch (request.getAction()) {
				case FIND_TABLES -> requireText(request.getQuery(), "FIND_TABLES 需要 query 参数");
				case GET_TABLE_SCHEMA, GET_RELATED_TABLES, PREVIEW_ROWS ->
					requireText(request.getTableName(), request.getAction().name() + " 需要 tableName 参数");
				case SEARCH -> requireText(request.getSql(), "SEARCH 需要 sql 参数");
				case LIST_TABLES -> {
				}
				default -> throw new IllegalArgumentException(objectToJson(
						ToolError.of(ToolErrorCode.UNSUPPORTED_ACTION, "不支持的数据源探索动作：" + request.getAction())));
			}
		}

		private void requireText(String value, String message) {
			if (!StringUtils.isNotBlank(value)) {
				throw new IllegalArgumentException(objectToJson(ToolError.of(ToolErrorCode.INVALID_INPUT, message)));
			}
		}

		private String objectToJson(Object value) {
			try {
				return objectMapper.writeValueAsString(value);
			}
			catch (Exception ex) {
				return "{\"code\":\"EXECUTION_FAILED\",\"message\":\"工具错误序列化失败\"}";
			}
		}

	}

}
