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

import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.alibaba.cloud.ai.dataagent.capability.metric.MetricUtils.firstNonBlank;

@Slf4j
@Component("openapi3")
@RequiredArgsConstructor
class MetricOpenApiParser implements MetricMetadataParser {

	private final ObjectMapper objectMapper;

	private final MetricCapabilityProperties properties;

	private final ThreadLocal<String> currentPath = new ThreadLocal<>();

	private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "delete", "patch");

	private static final int MAX_SKIP_LOG_SAMPLES = 10;

	@Override
	public String formatName() {
		return "openapi3";
	}

	@Override
	public ParsedMetricCatalog parse(String rawDocument) {
		try {
			com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(rawDocument);
			return doParse(root);
		}
		catch (Exception ex) {
			log.warn("Failed to parse metric OpenAPI document.", ex);
			return ParsedMetricCatalog.empty();
		}
	}

	public ParsedMetricCatalog parse(JsonNode root) {
		try {
			return doParse(root);
		}
		finally {
			currentPath.remove();
		}
	}

	private ParsedMetricCatalog doParse(JsonNode root) {
		if (root == null || root.isMissingNode() || root.path("paths").isMissingNode()) {
			log.warn("Metric OpenAPI parse skipped because paths node is missing.");
			return ParsedMetricCatalog.empty();
		}
		List<MetricCatalogEntry> entries = new ArrayList<>();
		List<String> skippedSamples = new ArrayList<>();
		Map<String, Integer> skippedReasons = new LinkedHashMap<>();
		int totalOperations = 0;
		JsonNode pathsNode = root.path("paths");
		var pathIterator = pathsNode.fieldNames();
		while (pathIterator.hasNext()) {
			String path = pathIterator.next();
			JsonNode pathNode = pathsNode.path(path);
			for (String method : HTTP_METHODS) {
				JsonNode operation = pathNode.path(method);
				if (operation.isMissingNode() || operation.isNull()) {
					continue;
				}
				totalOperations++;
				ParseOutcome outcome = toDefinition(root, path, method.toUpperCase(Locale.ROOT), operation);
				if (outcome.entry() != null) {
					entries.add(outcome.entry());
				}
				else {
					skippedReasons.merge(outcome.skipReason(), 1, Integer::sum);
					if (skippedSamples.size() < MAX_SKIP_LOG_SAMPLES) {
						skippedSamples.add("%s %s -> %s".formatted(method.toUpperCase(Locale.ROOT), path,
								outcome.skipReason()));
					}
				}
			}
		}
		log.info(
				"Metric OpenAPI parsing completed. totalOperations={}, definitionCount={}, skippedReasons={}, samples={}, skippedSamples={}",
				totalOperations, entries.size(), skippedReasons, summarizeDefinitions(entries), skippedSamples);
		validateUniqueIdentifiers(entries);
		return new ParsedMetricCatalog(entries);
	}

	private ParseOutcome toDefinition(JsonNode root, String path, String httpMethod, JsonNode operation) {
		String operationId = text(operation, "operationId");
		String summary = text(operation, "summary");
		String description = text(operation, "description");
		JsonNode responseSchema = resolveResponseSchema(root, operation);
		if (responseSchema == null || responseSchema.isNull() || responseSchema.isMissingNode()) {
			return ParseOutcome.skipped("missing response schema");
		}
		JsonNode requestSchema = resolveRequestSchema(root, operation);
		if (!isMetricOperation(path, operation, requestSchema, responseSchema)) {
			return ParseOutcome.skipped("operation does not look like metric API");
		}
		String metricCode = firstNonBlank(text(operation, "x-metric-code"),
				extractSchemaNamedExample(responseSchema, "metricCode"),
				extractSchemaNamedExample(requestSchema, "metricCode"), operationId,
				buildFallbackMetricCode(httpMethod, path));
		String metricName = firstNonBlank(text(operation, "x-metric-name"),
				extractSchemaNamedExample(responseSchema, "metricName"),
				extractSchemaNamedExample(requestSchema, "metricName"), summary,
				firstMetricTag(readStringList(operation.path("tags"))), operationId, buildFallbackMetricName(path));
		List<MetricApiParameter> requestParameters = extractRequestParameters(root, operation, requestSchema);
		String metricKey = firstNonBlank(text(operation, "x-data-agent-metric-id"), metricCode, operationId,
				buildFallbackMetricCode(httpMethod, path));
		MetricDefinition definition = MetricDefinition.builder()
			.metricKey(metricKey)
			.metricCode(metricCode)
			.metricName(metricName)
			.aliases(buildAliases(operation, metricName))
			.summary(summary)
			.description(joinText(description,
					firstNonBlank(text(responseSchema, "description"), text(requestSchema, "description"))))
			.supportedGranularities(findEnumValues(requestSchema, "granularity", "period", "cycle"))
			.supportedDimensions(findNamedProperties(requestSchema, "dimension", "group", "groupBy"))
			.supportedFilters(findNamedProperties(requestSchema, "filter", "where"))
			.examples(extractExamples(operation))
			.tags(readStringList(operation.path("tags")))
			.lastSyncTime(Instant.now().toEpochMilli())
			.build();
		MetricApiContract contract = MetricApiContract.builder()
			.operationId(operationId)
			.httpMethod(httpMethod)
			.path(path)
			.requestParameters(requestParameters)
			.requestSchema(requestSchema == null ? NullNode.getInstance() : requestSchema)
			.response(MetricApiResponse.builder()
				.description(resolveResponseDescription(operation))
				.contentType(resolveResponseContentType(operation))
				.schema(responseSchema)
				.examples(extractResponseExamples(operation))
				.build())
			.build();
		return ParseOutcome.of(new MetricCatalogEntry(definition, contract,
				new MetricBinding(metricKey, contract.apiId()), MetricServiceStatus.ONLINE, false, "COMPLETED", null));
	}

	private List<MetricApiParameter> extractRequestParameters(JsonNode root, JsonNode operation,
			JsonNode requestSchema) {
		List<MetricApiParameter> parameters = new ArrayList<>();
		Set<String> seenKeys = new LinkedHashSet<>();
		for (JsonNode parameter : operation.path("parameters")) {
			JsonNode resolvedParameter = resolveParameter(root, parameter);
			JsonNode schema = resolveSchema(root, resolvedParameter.path("schema"));
			MetricApiParameter apiParameter = MetricApiParameter.builder()
				.name(text(resolvedParameter, "name"))
				.location(firstNonBlank(text(resolvedParameter, "in"), "query"))
				.jsonPath(text(resolvedParameter, "name"))
				.type(resolveSchemaType(schema))
				.format(text(schema, "format"))
				.required(resolvedParameter.path("required").asBoolean(false))
				.description(text(resolvedParameter, "description"))
				.enumValues(readEnumValues(schema))
				.example(firstNonBlank(text(resolvedParameter, "example"), text(schema, "example"),
						text(schema, "default"), firstEnumValue(schema.path("enum"))))
				.defaultValue(scalarValue(schema.path("default")))
				.build();
			addParameter(parameters, seenKeys, apiParameter);
		}
		collectBodyParameters(requestSchema, "", readRequiredFields(requestSchema), parameters, seenKeys);
		return List.copyOf(parameters);
	}

	private JsonNode resolveParameter(JsonNode root, JsonNode parameter) {
		if (parameter == null || parameter.isMissingNode() || parameter.isNull()) {
			return NullNode.getInstance();
		}
		if (parameter.has("$ref")) {
			return resolveSchema(root, parameter);
		}
		return parameter;
	}

	private void collectBodyParameters(JsonNode node, String path, Set<String> requiredFields,
			List<MetricApiParameter> parameters, Set<String> seenKeys) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return;
		}
		String type = resolveSchemaType(node);
		if (!StringUtils.hasText(type)) {
			type = node.has("properties") ? "object" : node.has("items") ? "array" : "";
		}
		if ("object".equalsIgnoreCase(type) && node.has("properties")) {
			node.path("properties").fields().forEachRemaining(entry -> {
				String childPath = StringUtils.hasText(path) ? path + "." + entry.getKey() : entry.getKey();
				Set<String> childRequired = readRequiredFields(entry.getValue());
				boolean required = requiredFields.contains(entry.getKey());
				JsonNode childNode = entry.getValue();
				String childType = resolveSchemaType(childNode);
				if ("object".equalsIgnoreCase(childType) && childNode.has("properties")) {
					collectBodyParameters(childNode, childPath, childRequired, parameters, seenKeys);
					if (required && !hasRequiredDescendant(parameters, childPath)) {
						addParameter(parameters, seenKeys, buildBodyParameter(entry.getKey(), childPath, childNode, true));
					}
					return;
				}
				addParameter(parameters, seenKeys, buildBodyParameter(entry.getKey(), childPath, childNode, required));
				if (childNode.has("items") && childNode.path("items").has("properties")) {
					collectBodyParameters(childNode.path("items"), childPath, childRequired, parameters, seenKeys);
				}
			});
		}
	}

	private boolean hasRequiredDescendant(List<MetricApiParameter> parameters, String parentPath) {
		return parameters.stream()
			.anyMatch(parameter -> parameter.required() && StringUtils.hasText(parameter.jsonPath())
					&& parameter.jsonPath().startsWith(parentPath + "."));
	}

	private MetricApiParameter buildBodyParameter(String name, String jsonPath, JsonNode schema, boolean required) {
		return MetricApiParameter.builder()
			.name(name)
			.location("body")
			.jsonPath(jsonPath)
			.type(resolveSchemaType(schema))
			.format(text(schema, "format"))
			.required(required)
			.description(text(schema, "description"))
			.enumValues(readEnumValues(schema))
			.example(firstNonBlank(text(schema, "example"), text(schema, "default"), firstEnumValue(schema.path("enum"))))
			.defaultValue(scalarValue(schema.path("default")))
			.build();
	}

	private void addParameter(List<MetricApiParameter> parameters, Set<String> seenKeys, MetricApiParameter parameter) {
		if (parameter == null || !StringUtils.hasText(parameter.name())) {
			return;
		}
		String key = parameter.location() + ":" + firstNonBlank(parameter.jsonPath(), parameter.name());
		if (seenKeys.add(key)) {
			parameters.add(parameter);
		}
	}

	private Set<String> readRequiredFields(JsonNode schema) {
		Set<String> required = new LinkedHashSet<>();
		if (schema == null || schema.isMissingNode() || schema.isNull() || !schema.path("required").isArray()) {
			return required;
		}
		for (JsonNode item : schema.path("required")) {
			if (item != null && item.isValueNode() && StringUtils.hasText(item.asText())) {
				required.add(item.asText().trim());
			}
		}
		return required;
	}

	private List<String> readEnumValues(JsonNode schema) {
		if (schema == null || schema.isMissingNode() || schema.isNull() || !schema.path("enum").isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : schema.path("enum")) {
			if (item != null && item.isValueNode()) {
				values.add(item.asText());
			}
		}
		return List.copyOf(values);
	}

	private String resolveSchemaType(JsonNode schema) {
		if (schema == null || schema.isMissingNode() || schema.isNull()) {
			return "";
		}
		String type = text(schema, "type");
		if (StringUtils.hasText(type)) {
			return type;
		}
		if (schema.has("properties")) {
			return "object";
		}
		if (schema.has("items")) {
			return "array";
		}
		return "";
	}

	private Object scalarValue(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		return node.isValueNode() ? objectMapper.convertValue(node, Object.class) : null;
	}

	private JsonNode resolveRequestSchema(JsonNode root, JsonNode operation) {
		JsonNode requestBody = operation.path("requestBody");
		if (!requestBody.isMissingNode() && !requestBody.isNull()) {
			JsonNode resolved = resolveContentSchema(root, requestBody.path("content"));
			if (resolved != null && !resolved.isMissingNode() && !resolved.isNull()) {
				return resolved;
			}
		}
		ArrayNode parameters = objectMapper.createArrayNode();
		for (JsonNode parameter : operation.path("parameters")) {
			ObjectNode parameterNode = objectMapper.createObjectNode();
			parameterNode.put("name", text(parameter, "name"));
			parameterNode.put("in", text(parameter, "in"));
			parameterNode.set("schema", resolveSchema(root, parameter.path("schema")));
			parameters.add(parameterNode);
		}
		return parameters.isEmpty() ? NullNode.getInstance() : parameters;
	}

	private JsonNode resolveResponseSchema(JsonNode root, JsonNode operation) {
		JsonNode responses = operation.path("responses");
		for (String status : List.of("200", "201", "default")) {
			JsonNode response = responses.path(status);
			if (response.isMissingNode() || response.isNull()) {
				continue;
			}
			JsonNode resolved = resolveContentSchema(root, response.path("content"));
			if (resolved != null && !resolved.isMissingNode() && !resolved.isNull()) {
				return resolved;
			}
		}
		return NullNode.getInstance();
	}

	private String resolveResponseDescription(JsonNode operation) {
		JsonNode response = preferredResponse(operation);
		return response.isMissingNode() || response.isNull() ? "" : text(response, "description");
	}

	private String resolveResponseContentType(JsonNode operation) {
		JsonNode content = preferredResponse(operation).path("content");
		for (String preferredType : List.of("application/json", "*/*")) {
			if (content.has(preferredType)) {
				return preferredType;
			}
		}
		if (content.isObject() && content.fields().hasNext()) {
			return content.fields().next().getKey();
		}
		return "application/json";
	}

	private JsonNode preferredResponse(JsonNode operation) {
		JsonNode responses = operation.path("responses");
		for (String status : List.of("200", "201", "default")) {
			JsonNode response = responses.path(status);
			if (!response.isMissingNode() && !response.isNull()) {
				return response;
			}
		}
		return NullNode.getInstance();
	}

	private List<JsonNode> extractResponseExamples(JsonNode operation) {
		Set<String> serialized = new LinkedHashSet<>();
		collectExamples(preferredResponse(operation), serialized);
		List<JsonNode> examples = new ArrayList<>(serialized.size());
		for (String value : serialized) {
			try {
				examples.add(objectMapper.readTree(value));
			}
			catch (Exception ex) {
				examples.add(objectMapper.getNodeFactory().textNode(value));
			}
		}
		return List.copyOf(examples);
	}

	private JsonNode resolveContentSchema(JsonNode root, JsonNode contentNode) {
		if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
			return NullNode.getInstance();
		}
		for (String preferredType : List.of("application/json", "*/*")) {
			JsonNode resolved = resolveSchema(root, contentNode.path(preferredType).path("schema"));
			if (resolved != null && !resolved.isMissingNode() && !resolved.isNull()) {
				return resolved;
			}
		}
		if (contentNode.isObject()) {
			var contentIterator = contentNode.fields();
			while (contentIterator.hasNext()) {
				Map.Entry<String, JsonNode> entry = contentIterator.next();
				JsonNode resolved = resolveSchema(root, entry.getValue().path("schema"));
				if (resolved != null && !resolved.isMissingNode() && !resolved.isNull()) {
					log.info("Metric OpenAPI content schema resolved from fallback media type. mediaType={}",
							entry.getKey());
					return resolved;
				}
			}
		}
		return NullNode.getInstance();
	}

	private JsonNode resolveSchema(JsonNode root, JsonNode schema) {
		if (schema == null || schema.isMissingNode() || schema.isNull()) {
			return NullNode.getInstance();
		}
		if (schema.has("$ref")) {
			String ref = schema.path("$ref").asText();
			if (ref.startsWith("#/")) {
				JsonNode target = root;
				for (String part : ref.substring(2).split("/")) {
					target = target.path(part);
				}
				return target.isMissingNode() ? NullNode.getInstance() : resolveSchema(root, target.deepCopy());
			}
		}
		if (schema.isObject()) {
			ObjectNode copy = ((ObjectNode) schema).deepCopy();
			if (copy.has("properties")) {
				ObjectNode properties = objectMapper.createObjectNode();
				copy.path("properties")
					.fields()
					.forEachRemaining(entry -> properties.set(entry.getKey(), resolveSchema(root, entry.getValue())));
				copy.set("properties", properties);
			}
			if (copy.has("items")) {
				copy.set("items", resolveSchema(root, copy.path("items")));
			}
			for (String key : List.of("allOf", "anyOf", "oneOf")) {
				if (!copy.has(key)) {
					continue;
				}
				ArrayNode resolvedArray = objectMapper.createArrayNode();
				for (JsonNode item : copy.path(key)) {
					resolvedArray.add(resolveSchema(root, item));
				}
				copy.set(key, resolvedArray);
			}
			return copy;
		}
		if (schema.isArray()) {
			ArrayNode arrayNode = objectMapper.createArrayNode();
			for (JsonNode item : schema) {
				arrayNode.add(resolveSchema(root, item));
			}
			return arrayNode;
		}
		return schema;
	}

	private List<String> extractExamples(JsonNode operation) {
		Set<String> examples = new LinkedHashSet<>();
		collectExamples(operation.path("requestBody"), examples);
		collectExamples(operation.path("responses"), examples);
		return List.copyOf(examples);
	}

	private void collectExamples(JsonNode node, Set<String> examples) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return;
		}
		if (node.has("example")) {
			examples.add(node.path("example").toString());
		}
		if (node.has("examples")) {
			node.path("examples").fields().forEachRemaining(entry -> {
				JsonNode value = entry.getValue();
				if (value.has("value")) {
					examples.add(value.path("value").toString());
				}
			});
		}
		if (node.isObject()) {
			node.fields().forEachRemaining(entry -> collectExamples(entry.getValue(), examples));
		}
		if (node.isArray()) {
			for (JsonNode item : node) {
				collectExamples(item, examples);
			}
		}
	}

	private List<String> findEnumValues(JsonNode schema, String... keywords) {
		Set<String> values = new LinkedHashSet<>();
		walkSchema(schema, "", node -> {
			if (!node.has("enum")) {
				return;
			}
			String path = currentPath.get();
			if (!matchesKeyword(path, keywords)) {
				return;
			}
			for (JsonNode enumValue : node.path("enum")) {
				if (enumValue.isValueNode()) {
					values.add(enumValue.asText());
				}
			}
		});
		return List.copyOf(values);
	}

	private List<String> findNamedProperties(JsonNode schema, String... keywords) {
		Set<String> values = new LinkedHashSet<>();
		walkSchema(schema, "", node -> {
			String path = currentPath.get();
			if (matchesKeyword(path, keywords)) {
				String last = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
				if (StringUtils.hasText(last)) {
					values.add(last);
				}
			}
		});
		return List.copyOf(values);
	}

	private void walkSchema(JsonNode node, String path, java.util.function.Consumer<JsonNode> consumer) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return;
		}
		String previousPath = currentPath.get();
		try {
			currentPath.set(path == null ? "" : path);
			consumer.accept(node);
			if (node.isObject()) {
				if (node.has("properties")) {
					node.path("properties")
						.fields()
						.forEachRemaining(
								entry -> walkSchema(entry.getValue(), joinPath(path, entry.getKey()), consumer));
				}
				if (node.has("items")) {
					walkSchema(node.path("items"), joinPath(path, "items"), consumer);
				}
				for (String key : List.of("allOf", "anyOf", "oneOf")) {
					for (JsonNode item : node.path(key)) {
						walkSchema(item, path, consumer);
					}
				}
			}
			if (node.isArray()) {
				for (JsonNode item : node) {
					walkSchema(item, path, consumer);
				}
			}
		}
		finally {
			currentPath.set(previousPath);
		}
	}

	private boolean matchesKeyword(String value, String... keywords) {
		if (!StringUtils.hasText(value) || keywords == null) {
			return false;
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		for (String keyword : keywords) {
			if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private String joinPath(String path, String segment) {
		if (!StringUtils.hasText(path)) {
			return segment;
		}
		return path + "." + segment;
	}

	private List<String> readStringList(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		if (node.isArray()) {
			for (JsonNode item : node) {
				if (item.isValueNode() && StringUtils.hasText(item.asText())) {
					values.add(item.asText());
				}
			}
		}
		else if (node.isValueNode() && StringUtils.hasText(node.asText())) {
			values.add(node.asText());
		}
		return values;
	}

	private boolean isMetricOperation(String path, JsonNode operation, JsonNode requestSchema, JsonNode responseSchema) {
		if (StringUtils.hasText(text(operation, "x-metric-code"))
				|| StringUtils.hasText(text(operation, "x-metric-name"))) {
			return true;
		}
		if (StringUtils.hasText(extractSchemaNamedExample(responseSchema, "metricCode", "metricName"))
				|| StringUtils.hasText(extractSchemaNamedExample(requestSchema, "metricCode", "metricName"))) {
			return true;
		}
		String searchableText = joinText(path, text(operation, "summary"), text(operation, "description"),
				String.join(" ", readStringList(operation.path("tags"))));
		List<String> keywords = properties.getMetricKeywords();
		return keywords == null || keywords.isEmpty()
				? matchesKeyword(searchableText, "metric", "指标")
				: matchesKeyword(searchableText, keywords.toArray(new String[0]));
	}

	private List<String> buildAliases(JsonNode operation, String metricName) {
		Set<String> aliases = new LinkedHashSet<>();
		aliases.addAll(readStringList(operation.path("x-aliases")));
		addIfDifferent(aliases, text(operation, "summary"), metricName);
		addIfDifferent(aliases, text(operation, "operationId"), metricName);
		return List.copyOf(aliases);
	}

	private void addIfDifferent(Set<String> values, String candidate, String currentMetricName) {
		if (StringUtils.hasText(candidate) && !candidate.trim().equalsIgnoreCase(firstNonBlank(currentMetricName))) {
			values.add(candidate.trim());
		}
	}

	private String firstMetricTag(List<String> tags) {
		if (tags == null || tags.isEmpty()) {
			return "";
		}
		for (String tag : tags) {
			if (matchesKeyword(tag, "metric", "metrics", "指标")) {
				return tag;
			}
		}
		return "";
	}

	private String extractSchemaNamedExample(JsonNode schema, String... fieldNames) {
		if (schema == null || schema.isMissingNode() || schema.isNull() || fieldNames == null
				|| fieldNames.length == 0) {
			return "";
		}
		Set<String> normalizedNames = new LinkedHashSet<>();
		for (String fieldName : fieldNames) {
			if (StringUtils.hasText(fieldName)) {
				normalizedNames.add(fieldName.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (normalizedNames.isEmpty()) {
			return "";
		}
		AtomicBoolean found = new AtomicBoolean(false);
		String[] match = { "" };
		walkSchema(schema, "", node -> {
			if (found.get()) {
				return;
			}
			String current = currentPath.get();
			String fieldName = current == null || !current.contains(".") ? current
					: current.substring(current.lastIndexOf('.') + 1);
			if (!StringUtils.hasText(fieldName) || !normalizedNames.contains(fieldName.toLowerCase(Locale.ROOT))) {
				return;
			}
			String extracted = firstNonBlank(text(node, "example"), text(node, "default"), text(node, "const"),
					firstEnumValue(node.path("enum")));
			if (StringUtils.hasText(extracted)) {
				match[0] = extracted;
				found.set(true);
			}
		});
		return match[0];
	}

	private String firstEnumValue(JsonNode enumNode) {
		if (enumNode == null || enumNode.isMissingNode() || enumNode.isNull() || !enumNode.isArray()
				|| enumNode.isEmpty()) {
			return "";
		}
		JsonNode first = enumNode.get(0);
		return first == null || first.isNull() || first.isMissingNode() ? "" : first.asText("");
	}

	private String text(JsonNode node, String fieldName) {
		JsonNode field = node.path(fieldName);
		return field.isMissingNode() || field.isNull() ? "" : field.asText("");
	}

	private String joinText(String... values) {
		List<String> parts = new ArrayList<>();
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				parts.add(value.trim());
			}
		}
		return String.join("\n", parts);
	}

	private String buildFallbackMetricCode(String method, String path) {
		return (method + "_" + path).replaceAll("[^A-Za-z0-9]+", "_")
			.replaceAll("_+", "_")
			.replaceAll("^_|_$", "")
			.toLowerCase(Locale.ROOT);
	}

	private String buildFallbackMetricName(String path) {
		String normalized = path.replace('{', ' ').replace('}', ' ').replace('/', ' ').replace('-', ' ').trim();
		return StringUtils.hasText(normalized) ? normalized : "metric";
	}

	private void validateUniqueIdentifiers(List<MetricCatalogEntry> entries) {
		Set<String> metricKeys = new LinkedHashSet<>();
		Set<String> operationIds = new LinkedHashSet<>();
		for (MetricCatalogEntry entry : entries) {
			String metricKey = entry.definition().metricKey();
			String operationId = entry.contract().apiId();
			if (!StringUtils.hasText(metricKey) || !metricKeys.add(metricKey)) {
				throw new IllegalArgumentException("指标 metricKey 为空或重复：" + metricKey);
			}
			if (!StringUtils.hasText(operationId) || !operationIds.add(operationId)) {
				throw new IllegalArgumentException("指标 operationId 为空或重复：" + operationId);
			}
		}
	}

	private List<String> summarizeDefinitions(List<MetricCatalogEntry> entries) {
		if (entries == null || entries.isEmpty()) {
			return List.of();
		}
		return entries.stream()
			.limit(5)
			.map(entry -> "%s/%s".formatted(entry.definition().metricCode(), entry.definition().metricName()))
			.toList();
	}

	private record ParseOutcome(MetricCatalogEntry entry, String skipReason) {

		private static ParseOutcome of(MetricCatalogEntry entry) {
			return new ParseOutcome(entry, "");
		}

		private static ParseOutcome skipped(String skipReason) {
			return new ParseOutcome(null, skipReason);
		}

	}

}
