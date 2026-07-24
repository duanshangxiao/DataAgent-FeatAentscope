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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Parser for the stable DataAgent metric catalog contract. It deliberately consumes
 * the top-level invocation parameter list instead of expanding request-schema leaves.
 */
@Component
@RequiredArgsConstructor
public class DataMetricsCatalogParser implements MetricMetadataParser {

	public static final String FORMAT = "data-agent-catalog-v1";

	public static final String SPEC_VERSION = "data-agent-metric-catalog/1.0";

	private static final String DATA_METRICS_SOURCE = "data-metrics";

	private final ObjectMapper objectMapper;

	@Override
	public ParsedMetricCatalog parse(String rawDocument) {
		try {
			JsonNode root = objectMapper.readTree(rawDocument);
			requireObject(root, "catalog root");
			String specVersion = text(root, "specVersion");
			if (!SPEC_VERSION.equals(specVersion)) {
				throw new IllegalArgumentException("Unsupported metric catalog specVersion: " + specVersion);
			}
			String sourceSystem = text(root, "sourceSystem");
			requireText(sourceSystem, "sourceSystem");
			JsonNode metrics = root.path("metrics");
			if (!metrics.isArray() || metrics.isEmpty()) {
				throw new IllegalArgumentException("Metric catalog metrics must be a non-empty array.");
			}
			long syncTime = System.currentTimeMillis();
			List<MetricCatalogEntry> entries = new ArrayList<>(metrics.size());
			for (int index = 0; index < metrics.size(); index++) {
				entries.add(parseEntry(metrics.get(index), sourceSystem, syncTime, index));
			}
			return new ParsedMetricCatalog(entries);
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid metric catalog JSON: " + ex.getMessage(), ex);
		}
	}

	private MetricCatalogEntry parseEntry(JsonNode metric, String sourceSystem, long syncTime, int index) {
		requireObject(metric, "metrics[" + index + "]");
		JsonNode definitionNode = metric.path("definition");
		JsonNode invocationNode = metric.path("invocation");
		requireObject(definitionNode, "metrics[" + index + "].definition");
		requireObject(invocationNode, "metrics[" + index + "].invocation");

		MetricDefinition definition = MetricDefinition.builder()
			.metricKey(text(definitionNode, "metricKey"))
			.metricCode(text(definitionNode, "metricCode"))
			.metricName(text(definitionNode, "metricName"))
			.aliases(stringList(definitionNode.path("aliases")))
			.summary(firstText(definitionNode, "summary", invocationNode, "summary"))
			.description(firstText(definitionNode, "description", invocationNode, "description"))
			.supportedGranularities(stringList(definitionNode.path("supportedGranularities")))
			.supportedDimensions(stringList(definitionNode.path("supportedDimensions")))
			.supportedFilters(stringList(definitionNode.path("supportedFilters")))
			.examples(stringList(definitionNode.path("examples")))
			.tags(stringList(definitionNode.path("tags")))
			.lastSyncTime(syncTime)
			.build();

		String operationId = text(invocationNode, "operationId");
		MetricApiResponse response = parseResponse(invocationNode.path("response"), sourceSystem);
		MetricApiContract contract = MetricApiContract.builder()
			.operationId(operationId)
			.httpMethod(text(invocationNode, "method").toUpperCase(Locale.ROOT))
			.path(text(invocationNode, "path"))
			.requestParameters(parseParameters(invocationNode.path("parameters")))
			.requestSchema(copyOrNull(invocationNode.get("requestSchema")))
			.response(response)
			.build();
		rejectUnsupportedNullOperators(sourceSystem, contract);
		return new MetricCatalogEntry(definition, contract,
				new MetricBinding(definition.metricKey(), operationId), MetricServiceStatus.ONLINE, false,
				"COMPLETED", null);
	}

	private List<MetricApiParameter> parseParameters(JsonNode node) {
		if (!node.isArray()) {
			throw new IllegalArgumentException("invocation.parameters must be an array.");
		}
		List<MetricApiParameter> parameters = new ArrayList<>(node.size());
		for (JsonNode parameter : node) {
			requireObject(parameter, "invocation.parameters[]");
			parameters.add(MetricApiParameter.builder()
				.name(text(parameter, "name"))
				.location(text(parameter, "location"))
				.jsonPath(text(parameter, "jsonPath"))
				.type(text(parameter, "type"))
				.format(text(parameter, "format"))
				.required(parameter.path("required").asBoolean(false))
				.description(text(parameter, "description"))
				.enumValues(stringList(parameter.path("enumValues")))
				.example(value(parameter.get("example")))
				.defaultValue(value(parameter.get("defaultValue")))
				.build());
		}
		return parameters;
	}

	private MetricApiResponse parseResponse(JsonNode node, String sourceSystem) {
		requireObject(node, "invocation.response");
		MetricSuccessCriteria criteria = null;
		JsonNode criteriaNode = node.path("successCriteria");
		if (criteriaNode.isObject()) {
			criteria = MetricSuccessCriteria.builder()
				.jsonPath(text(criteriaNode, "jsonPath"))
				.operator(text(criteriaNode, "operator"))
				.expectedValue(copyOrNull(criteriaNode.get("expectedValue")))
				.build();
		}
		String resultPath = text(node, "resultPath");
		String messagePath = text(node, "messagePath");
		if (DATA_METRICS_SOURCE.equals(sourceSystem)) {
			if (criteria == null) {
				criteria = MetricSuccessCriteria.builder()
					.jsonPath("$.code")
					.operator("EQ")
					.expectedValue(objectMapper.valueToTree(0))
					.build();
			}
			resultPath = StringUtils.hasText(resultPath) ? resultPath : "$.data.list";
			messagePath = StringUtils.hasText(messagePath) ? messagePath : "$.msg";
		}
		return MetricApiResponse.builder()
			.description(text(node, "description"))
			.contentType(text(node, "contentType"))
			.schema(copyOrNull(node.get("schema")))
			.examples(jsonList(node.path("examples")))
			.successCriteria(criteria)
			.resultPath(resultPath)
			.messagePath(messagePath)
			.build();
	}

	private void rejectUnsupportedNullOperators(String sourceSystem, MetricApiContract contract) {
		if (!DATA_METRICS_SOURCE.equals(sourceSystem)) {
			return;
		}
		if (containsUnsupportedOperator(contract.requestSchema())) {
			throw new IllegalArgumentException(
					"data-metrics v1 requestSchema must not publish isNull or isNotNull operators.");
		}
		for (MetricApiParameter parameter : contract.requestParameters()) {
			if (parameter.enumValues().stream().anyMatch(this::unsupportedOperator)
					|| containsUnsupportedOperator(objectMapper.valueToTree(parameter.example()))
					|| containsUnsupportedOperator(objectMapper.valueToTree(parameter.defaultValue()))) {
				throw new IllegalArgumentException("data-metrics v1 parameter '" + parameter.name()
						+ "' must not publish isNull or isNotNull operators.");
			}
		}
	}

	private boolean containsUnsupportedOperator(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return false;
		}
		if (node.isTextual()) {
			return unsupportedOperator(node.asText());
		}
		if (node.isContainerNode()) {
			for (JsonNode child : node) {
				if (containsUnsupportedOperator(child)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean unsupportedOperator(String value) {
		return "isnull".equalsIgnoreCase(value) || "isnotnull".equalsIgnoreCase(value);
	}

	private JsonNode copyOrNull(JsonNode node) {
		return node == null || node.isMissingNode() ? NullNode.getInstance() : node.deepCopy();
	}

	private List<JsonNode> jsonList(JsonNode node) {
		if (!node.isArray()) {
			return List.of();
		}
		List<JsonNode> values = new ArrayList<>(node.size());
		node.forEach(value -> values.add(value.deepCopy()));
		return values;
	}

	private List<String> stringList(JsonNode node) {
		if (!node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>(node.size());
		node.forEach(value -> {
			if (value.isValueNode() && !value.isNull()) {
				values.add(value.asText());
			}
		});
		return values;
	}

	private Object value(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		return objectMapper.convertValue(node, Object.class);
	}

	private String firstText(JsonNode firstNode, String firstField, JsonNode secondNode, String secondField) {
		String first = text(firstNode, firstField);
		return StringUtils.hasText(first) ? first : text(secondNode, secondField);
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
	}

	private void requireObject(JsonNode node, String field) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(field + " must be an object.");
		}
	}

	private void requireText(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " must not be blank.");
		}
	}

	@Override
	public String formatName() {
		return FORMAT;
	}

}
