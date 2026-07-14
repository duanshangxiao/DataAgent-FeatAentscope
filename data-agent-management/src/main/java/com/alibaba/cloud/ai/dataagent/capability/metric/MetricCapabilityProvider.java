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
import com.alibaba.cloud.ai.dataagent.capability.CapabilityProvider;
import com.alibaba.cloud.ai.dataagent.capability.CapabilityRouteResult;
import com.alibaba.cloud.ai.dataagent.capability.CapabilityRouteType;
import com.alibaba.cloud.ai.dataagent.observability.AnswerTraceExplainStore;
import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.alibaba.cloud.ai.dataagent.service.skill.AgentSkillBindingService;
import com.alibaba.cloud.ai.dataagent.service.skill.LocalSkillService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricCapabilityProvider implements CapabilityProvider {

	public static final String CAPABILITY_ID = "metric-system";

	private final MetricCapabilityProperties properties;

	private final AgentSkillBindingService agentSkillBindingService;

	private final MetricCatalogIndex metricCatalogIndex;

	private final MetricToolProvider metricToolProvider;

	private final MetricOpenApiSyncService metricOpenApiSyncService;

	@Override
	public String capabilityId() {
		return CAPABILITY_ID;
	}

	@Override
	public boolean enabledForAgent(String agentId) {
		if (!properties.isEnabled()) {
			log.info("Metric capability disabled by configuration. agentId={}", agentId);
			return false;
		}
		if (!StringUtils.hasText(agentId)) {
			log.info("Metric capability disabled because agentId is empty.");
			return false;
		}
		if (!metricOpenApiSyncService.isReady()) {
			log.info("Metric capability disabled because catalog is not ready. agentId={}, swaggerUrl={}", agentId,
					properties.getSwaggerUrl());
			return false;
		}
		try {
			Long numericAgentId = Long.valueOf(agentId);
			List<String> skillIds = agentSkillBindingService.listSkillIdsByAgentId(numericAgentId);
			boolean enabled = skillIds.contains(LocalSkillService.BUILTIN_METRIC_SYSTEM_SKILL_ID);
			log.info("Metric capability binding checked. agentId={}, enabled={}, skillIds={}", agentId, enabled, skillIds);
			return enabled;
		}
		catch (NumberFormatException ex) {
			log.warn("Metric capability disabled because agentId is not numeric. agentId={}", agentId);
			return false;
		}
	}

	@Override
	public CapabilityRouteResult route(String agentId, String query) {
		if (!enabledForAgent(agentId)) {
			return CapabilityRouteResult.noMatch(capabilityId(), "指标能力未启用，不参与当前问题路由。");
		}
		if (!StringUtils.hasText(query)) {
			return CapabilityRouteResult.unknown(capabilityId(), 0D, List.of(), "问题为空，无法判断能力归属。");
		}
		List<MetricCatalogSearchCandidate> candidates = metricCatalogIndex.search(query, 3);
		double topScore = candidates.isEmpty() ? 0D : candidates.get(0).score();
		List<String> matchedTargets = candidates.stream().map(candidate -> candidate.definition().apiId()).toList();
		double clarifyThreshold = Math.max(0.35D, properties.getRouteThreshold() * 0.6D);
		boolean ambiguous = candidates.size() > 1
				&& candidates.get(1).score() >= Math.max(clarifyThreshold, topScore - 0.08D);
		log.info(
				"Metric capability routing evaluated. agentId={}, query={}, topScore={}, threshold={}, clarifyThreshold={}, ambiguous={}, candidates={}",
				agentId, query, topScore, properties.getRouteThreshold(), clarifyThreshold, ambiguous,
				candidates.stream()
					.map(candidate -> "%s(%.2f)".formatted(candidate.definition().apiId(), candidate.score()))
					.toList());
		if (topScore >= properties.getRouteThreshold() && !ambiguous) {
			return CapabilityRouteResult.builder()
				.routeType(CapabilityRouteType.METRIC_ONLY)
				.score(topScore)
				.matchedCapabilityId(capabilityId())
				.matchedTargets(matchedTargets)
				.reason("根据指标接口文档命中高置信度指标接口。")
				.build();
		}
		if (ambiguous) {
			return CapabilityRouteResult.unknown(capabilityId(), topScore, matchedTargets,
					"命中多个语义接近的指标接口，需要先确认具体查询目标。");
		}
		if (topScore >= clarifyThreshold) {
			return CapabilityRouteResult.unknown(capabilityId(), topScore, matchedTargets,
					"已命中候选指标接口，但接口匹配置信度不足，需要补充更多查询条件。");
		}
		return CapabilityRouteResult.noMatch(capabilityId(), "未命中指标接口文档中的候选接口。");
	}

	@Override
	public Map<String, ToolCallback> getToolCallbacks(String agentId) {
		return enabledForAgent(agentId) ? metricToolProvider.getToolCallbacks() : Map.of();
	}

	@Override
	public void refreshMetadata() {
		metricOpenApiSyncService.refreshCatalog();
	}

}

@Slf4j
@Service
@RequiredArgsConstructor
class MetricOpenApiSyncService {

	private final MetricCapabilityProperties properties;

	private final WebClient.Builder webClientBuilder;

	private final ObjectMapper objectMapper;

	private final MetricOpenApiParser metricOpenApiParser;

	private final MetricCatalogIndex metricCatalogIndex;

	private final AtomicReference<String> lastSuccessfulHash = new AtomicReference<>("");

	@EventListener(ApplicationReadyEvent.class)
	public void warmUp() {
		refreshCatalog();
	}

	@Scheduled(fixedDelayString = "#{T(java.lang.Long).parseLong('${spring.ai.alibaba.data-agent.capabilities.metric-system.refresh-interval-seconds:1800}') * 1000}")
	public void scheduledRefresh() {
		refreshCatalog();
	}

	public synchronized void refreshCatalog() {
		if (!properties.isEnabled()) {
			return;
		}
		if (!StringUtils.hasText(properties.getSwaggerUrl())) {
			log.warn("Metric capability enabled but swaggerUrl is empty.");
			return;
		}
		try {
			String rawDocument = webClientBuilder.build()
				.get()
				.uri(properties.getSwaggerUrl())
				.retrieve()
				.bodyToMono(String.class)
				.block();
			if (!StringUtils.hasText(rawDocument)) {
				log.warn("Metric OpenAPI document is empty, keep previous catalog snapshot.");
				return;
			}
			log.info("Fetched metric OpenAPI document successfully. swaggerUrl={}, byteSize={}", properties.getSwaggerUrl(),
					rawDocument.length());
			String currentHash = sha256(rawDocument);
			if (currentHash.equals(lastSuccessfulHash.get()) && metricCatalogIndex.isAvailable()) {
				return;
			}
			JsonNode root = objectMapper.readTree(rawDocument);
			List<MetricDefinition> definitions = metricOpenApiParser.parse(root);
			if (definitions.isEmpty()) {
				log.warn("Metric OpenAPI parsed but produced no valid metric definitions. swaggerUrl={}",
						properties.getSwaggerUrl());
				return;
			}
			metricCatalogIndex.refresh(definitions);
			lastSuccessfulHash.set(currentHash);
			log.info("Metric catalog refreshed successfully. definitionCount={}, samples={}", definitions.size(),
					summarizeDefinitions(definitions));
		}
		catch (Exception ex) {
			log.warn("Failed to refresh metric OpenAPI catalog, keep previous successful snapshot.", ex);
		}
	}

	public boolean isReady() {
		return metricCatalogIndex.isAvailable();
	}

	private String sha256(String value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder builder = new StringBuilder(hash.length * 2);
		for (byte current : hash) {
			builder.append(String.format("%02x", current));
		}
		return builder.toString();
	}

	private List<String> summarizeDefinitions(List<MetricDefinition> definitions) {
		if (definitions == null || definitions.isEmpty()) {
			return List.of();
		}
		return definitions.stream()
			.limit(5)
			.map(definition -> "%s/%s".formatted(definition.metricCode(), definition.metricName()))
			.toList();
	}

}

@Component
class MetricCatalogIndex {

	private final AtomicReference<Map<String, MetricDefinition>> definitionsRef = new AtomicReference<>(Map.of());

	public void refresh(Collection<MetricDefinition> definitions) {
		Map<String, MetricDefinition> refreshed = new LinkedHashMap<>();
		if (definitions != null) {
			for (MetricDefinition definition : definitions) {
				if (definition == null || !StringUtils.hasText(definition.apiId())) {
					continue;
				}
				refreshed.put(definition.apiId(), definition);
			}
		}
		definitionsRef.set(Map.copyOf(refreshed));
	}

	public boolean isAvailable() {
		return !definitionsRef.get().isEmpty();
	}

	public Optional<MetricDefinition> get(String identifier) {
		if (!StringUtils.hasText(identifier)) {
			return Optional.empty();
		}
		Map<String, MetricDefinition> definitions = definitionsRef.get();
		MetricDefinition direct = definitions.get(identifier.trim());
		if (direct != null) {
			return Optional.of(direct);
		}
		return definitions.values()
			.stream()
			.filter(definition -> identifier.trim().equalsIgnoreCase(definition.operationId())
					|| identifier.trim().equalsIgnoreCase(definition.metricCode())
					|| identifier.trim().equalsIgnoreCase(definition.path()))
			.findFirst();
	}

	public List<MetricCatalogSearchCandidate> search(String query, int limit) {
		if (!StringUtils.hasText(query) || definitionsRef.get().isEmpty()) {
			return List.of();
		}
		String normalizedQuery = normalize(query);
		List<String> keywords = tokenize(query);
		List<MetricCatalogSearchCandidate> candidates = new ArrayList<>();
		for (MetricDefinition definition : definitionsRef.get().values()) {
			double score = score(normalizedQuery, keywords, definition);
			if (score <= 0D) {
				continue;
			}
			candidates.add(MetricCatalogSearchCandidate.builder()
				.definition(definition)
				.score(Math.min(score, 1D))
				.matchedKeywords(matchKeywords(normalizedQuery, keywords, definition))
				.build());
		}
		return candidates.stream()
			.sorted(Comparator.comparingDouble(MetricCatalogSearchCandidate::score).reversed()
				.thenComparing(candidate -> candidate.definition().metricCode()))
			.limit(Math.max(limit, 1))
			.toList();
	}

	private double score(String normalizedQuery, List<String> keywords, MetricDefinition definition) {
		String haystack = searchableText(definition);
		double score = 0D;
		if (containsText(normalizedQuery, definition.summary())) {
			score += 0.45D;
		}
		if (containsText(normalizedQuery, definition.description())) {
			score += 0.30D;
		}
		if (containsText(normalizedQuery, definition.operationId())) {
			score += 0.10D;
		}
		for (String tag : definition.tags()) {
			if (containsText(normalizedQuery, tag)) {
				score += 0.15D;
			}
		}
		for (MetricApiParameter parameter : definition.requestParameters()) {
			if (containsText(normalizedQuery, parameter.name()) || containsText(normalizedQuery, parameter.description())) {
				score += parameter.required() ? 0.12D : 0.06D;
			}
		}
		int keywordHits = 0;
		for (String keyword : keywords) {
			String normalizedKeyword = normalize(keyword);
			if (normalizedKeyword.length() >= 2 && haystack.contains(normalizedKeyword)) {
				keywordHits++;
			}
		}
		if (!keywords.isEmpty()) {
			score += Math.min(0.4D, keywordHits * 1.0D / keywords.size() * 0.4D);
		}
		return score;
	}

	private List<String> matchKeywords(String normalizedQuery, List<String> keywords, MetricDefinition definition) {
		Set<String> matched = new LinkedHashSet<>();
		if (containsText(normalizedQuery, definition.summary())) {
			matched.add(definition.summary());
		}
		if (containsText(normalizedQuery, definition.description())) {
			matched.add(definition.description());
		}
		for (MetricApiParameter parameter : definition.requestParameters()) {
			if (containsText(normalizedQuery, parameter.name())) {
				matched.add(parameter.name());
			}
		}
		String haystack = searchableText(definition);
		for (String keyword : keywords) {
			String normalizedKeyword = normalize(keyword);
			if (normalizedKeyword.length() >= 2 && haystack.contains(normalizedKeyword)) {
				matched.add(keyword);
			}
		}
		return List.copyOf(matched);
	}

	private String searchableText(MetricDefinition definition) {
		StringBuilder builder = new StringBuilder();
		append(builder, definition.summary());
		append(builder, definition.description());
		append(builder, definition.operationId());
		append(builder, definition.path());
		definition.aliases().forEach(value -> append(builder, value));
		definition.tags().forEach(value -> append(builder, value));
		definition.requestParameters().forEach(parameter -> {
			append(builder, parameter.name());
			append(builder, parameter.description());
			append(builder, parameter.location());
			parameter.enumValues().forEach(value -> append(builder, value));
		});
		return normalize(builder.toString());
	}

	private void append(StringBuilder builder, String value) {
		if (StringUtils.hasText(value)) {
			builder.append(' ').append(value);
		}
	}

	private boolean containsText(String query, String value) {
		return StringUtils.hasText(value) && query.contains(normalize(value));
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim() : "";
	}

	private List<String> tokenize(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		String[] segments = value.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");
		Set<String> tokens = new LinkedHashSet<>();
		for (String segment : segments) {
			if (!StringUtils.hasText(segment)) {
				continue;
			}
			String trimmed = segment.trim();
			tokens.add(trimmed);
			if (trimmed.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FA5)) {
				addChineseNgrams(tokens, trimmed);
			}
		}
		return List.copyOf(tokens);
	}

	private void addChineseNgrams(Set<String> tokens, String segment) {
		if (!StringUtils.hasText(segment)) {
			return;
		}
		int maxGram = Math.min(4, segment.length());
		for (int size = 2; size <= maxGram; size++) {
			for (int index = 0; index + size <= segment.length(); index++) {
				tokens.add(segment.substring(index, index + size));
			}
		}
	}

}

@Slf4j
@Component
@RequiredArgsConstructor
class MetricOpenApiParser {

	private final ObjectMapper objectMapper;

	private final ThreadLocal<String> currentPath = new ThreadLocal<>();

	private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "delete", "patch");

	private static final int MAX_SKIP_LOG_SAMPLES = 10;

	public List<MetricDefinition> parse(JsonNode root) {
		if (root == null || root.isMissingNode() || root.path("paths").isMissingNode()) {
			log.warn("Metric OpenAPI parse skipped because paths node is missing.");
			return List.of();
		}
		List<MetricDefinition> definitions = new ArrayList<>();
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
				if (outcome.definition() != null) {
					definitions.add(outcome.definition());
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
		log.info("Metric OpenAPI parsing completed. totalOperations={}, definitionCount={}, skippedReasons={}, samples={}, skippedSamples={}",
				totalOperations, definitions.size(), skippedReasons, summarizeDefinitions(definitions), skippedSamples);
		return definitions;
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
		String metricCode = firstNonBlank(text(operation, "x-metric-code"), extractSchemaNamedExample(responseSchema, "metricCode"),
				extractSchemaNamedExample(requestSchema, "metricCode"), operationId, buildFallbackMetricCode(httpMethod, path));
		String metricName = firstNonBlank(text(operation, "x-metric-name"), extractSchemaNamedExample(responseSchema, "metricName"),
				extractSchemaNamedExample(requestSchema, "metricName"), summary, firstMetricTag(readStringList(operation.path("tags"))),
				operationId, buildFallbackMetricName(path));
		List<MetricApiParameter> requestParameters = extractRequestParameters(root, operation, requestSchema);
		return ParseOutcome.of(MetricDefinition.builder()
			.metricCode(metricCode)
			.metricName(metricName)
			.aliases(buildAliases(operation, metricName))
			.summary(summary)
			.description(joinText(description, firstNonBlank(text(responseSchema, "description"), text(requestSchema, "description"))))
			.operationId(operationId)
			.httpMethod(httpMethod)
			.path(path)
			.requestParameters(requestParameters)
			.requestSchema(requestSchema == null ? NullNode.getInstance() : requestSchema)
			.responseSchema(responseSchema)
			.supportedGranularities(findEnumValues(requestSchema, "granularity", "period", "cycle"))
			.supportedDimensions(findNamedProperties(requestSchema, "dimension", "group", "groupBy"))
			.supportedFilters(findNamedProperties(requestSchema, "filter", "where"))
			.examples(extractExamples(operation))
			.tags(readStringList(operation.path("tags")))
			.lastSyncTime(Instant.now().toEpochMilli())
			.build());
	}

	private List<MetricApiParameter> extractRequestParameters(JsonNode root, JsonNode operation, JsonNode requestSchema) {
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
				.required(resolvedParameter.path("required").asBoolean(false))
				.description(text(resolvedParameter, "description"))
				.enumValues(readEnumValues(schema))
				.example(firstNonBlank(text(resolvedParameter, "example"), text(schema, "example"), text(schema, "default"),
						firstEnumValue(schema.path("enum"))))
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
					log.info("Metric OpenAPI content schema resolved from fallback media type. mediaType={}", entry.getKey());
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
		currentPath.set(path == null ? "" : path);
		consumer.accept(node);
		if (node.isObject()) {
			if (node.has("properties")) {
				node.path("properties")
					.fields()
					.forEachRemaining(entry -> walkSchema(entry.getValue(), joinPath(path, entry.getKey()), consumer));
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
		currentPath.set(previousPath);
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
		if (StringUtils.hasText(text(operation, "x-metric-code")) || StringUtils.hasText(text(operation, "x-metric-name"))) {
			return true;
		}
		if (StringUtils.hasText(extractSchemaNamedExample(responseSchema, "metricCode", "metricName"))
				|| StringUtils.hasText(extractSchemaNamedExample(requestSchema, "metricCode", "metricName"))) {
			return true;
		}
		String searchableText = joinText(path, text(operation, "summary"), text(operation, "description"),
				String.join(" ", readStringList(operation.path("tags"))));
		return matchesKeyword(searchableText, "metric", "metrics", "指标", "gmv", "dau", "mau", "留存", "活跃");
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
		if (schema == null || schema.isMissingNode() || schema.isNull() || fieldNames == null || fieldNames.length == 0) {
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
		AtomicReference<String> match = new AtomicReference<>("");
		walkSchema(schema, "", node -> {
			if (StringUtils.hasText(match.get())) {
				return;
			}
			String current = currentPath.get();
			String fieldName = current == null || !current.contains(".") ? current
					: current.substring(current.lastIndexOf('.') + 1);
			if (!StringUtils.hasText(fieldName) || !normalizedNames.contains(fieldName.toLowerCase(Locale.ROOT))) {
				return;
			}
			match.set(firstNonBlank(text(node, "example"), text(node, "default"), text(node, "const"),
					firstEnumValue(node.path("enum"))));
		});
		return match.get();
	}

	private String firstEnumValue(JsonNode enumNode) {
		if (enumNode == null || enumNode.isMissingNode() || enumNode.isNull() || !enumNode.isArray() || enumNode.isEmpty()) {
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

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
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

	private List<String> summarizeDefinitions(List<MetricDefinition> definitions) {
		if (definitions == null || definitions.isEmpty()) {
			return List.of();
		}
		return definitions.stream()
			.limit(5)
			.map(definition -> "%s/%s".formatted(definition.metricCode(), definition.metricName()))
			.toList();
	}

	private record ParseOutcome(MetricDefinition definition, String skipReason) {

		private static ParseOutcome of(MetricDefinition definition) {
			return new ParseOutcome(definition, "");
		}

		private static ParseOutcome skipped(String skipReason) {
			return new ParseOutcome(null, skipReason);
		}

	}

}

@Slf4j
@Component
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

	private final MetricCatalogIndex metricCatalogIndex;

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
			.description("根据自然语言问题检索候选指标接口。")
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
				int limit = input.path("limit").asInt(5);
				log.info("Metric catalog search invoked. query={}, limit={}", query, limit);
				List<MetricCatalogSearchCandidate> candidates = metricCatalogIndex.search(query, limit);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("summary", "共匹配到 %d 个候选指标接口".formatted(candidates.size()));
				result.put("candidates", candidates.stream().map(candidate -> {
					MetricDefinition definition = candidate.definition();
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("apiId", definition.apiId());
					item.put("operationId", definition.operationId());
					item.put("summary", definition.summary());
					item.put("httpMethod", definition.httpMethod());
					item.put("path", definition.path());
					item.put("metricCode", definition.metricCode());
					item.put("metricName", definition.metricName());
					item.put("score", candidate.score());
					item.put("description", definition.description());
					item.put("requiredParameters", definition.requiredParameters()
						.stream()
						.map(MetricApiParameter::name)
						.toList());
					item.put("requestParameters", definition.requestParameters());
					item.put("matchedKeywords", candidate.matchedKeywords());
					return item;
				}).toList());
				log.info("Metric catalog search completed. query={}, matchedApiIds={}", query,
						candidates.stream().map(candidate -> candidate.definition().apiId()).toList());
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				answerTraceExplainStore.recordMetricCatalogSearch(agentRequest, query, (String) result.get("summary"),
						candidates.stream().map(candidate -> candidate.definition().apiId()).toList());
				return objectMapper.writeValueAsString(result);
			}
			catch (Exception ex) {
				log.warn("Metric catalog search failed. toolInput={}", toolInput, ex);
				throw new IllegalStateException("指标目录检索失败：" + ex.getMessage(), ex);
			}
		}

	}

	private final class MetricDescribeToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(DESCRIBE_TOOL)
			.description("返回某个指标接口的完整契约定义。")
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
				String identifier = pickIdentifier(input.path("operationId").asText(), input.path("metricCode").asText());
				log.info("Metric catalog describe invoked. identifier={}", identifier);
				MetricDefinition definition = metricCatalogIndex.get(identifier)
					.orElseThrow(() -> new IllegalArgumentException("未找到指标接口定义：" + identifier));
				return objectMapper.writeValueAsString(definition);
			}
			catch (Exception ex) {
				log.warn("Metric catalog describe failed. toolInput={}", toolInput, ex);
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
				log.info("Metric query execute invoked. operationId={}, metricCode={}, timeRange={}, limit={}",
						request.getOperationId(), request.getMetricCode(), request.getTimeRange(), request.getLimit());
				MetricQueryResult result = metricQueryExecutionService.execute(request);
				log.info("Metric query execute completed. identifier={}, status={}, summary={}, rowCount={}",
						pickIdentifier(request.getOperationId(), request.getMetricCode()), result.status(), result.summary(),
						result.rows() == null ? 0 : result.rows().size());
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				answerTraceExplainStore.recordMetricQueryResult(agentRequest,
						pickIdentifier(request.getOperationId(), request.getMetricCode()), result.summary());
				return objectMapper.writeValueAsString(result);
			}
			catch (Exception ex) {
				log.warn("Metric query execute failed. toolInput={}", toolInput, ex);
				throw new IllegalStateException("指标查询失败：" + ex.getMessage(), ex);
			}
		}

	}

}

@Slf4j
@Service
@RequiredArgsConstructor
class MetricQueryExecutionService {

	private final MetricCatalogIndex metricCatalogIndex;

	private final MetricCapabilityProperties properties;

	private final WebClient.Builder webClientBuilder;

	private final ObjectMapper objectMapper;

	public MetricQueryResult execute(MetricQueryRequest request) {
		String identifier = request == null ? "" : firstNonBlank(request.getOperationId(), request.getMetricCode());
		if (!StringUtils.hasText(identifier)) {
			throw new IllegalArgumentException("operationId 或 metricCode 不能为空。");
		}
		MetricDefinition definition = metricCatalogIndex.get(identifier)
			.orElseThrow(() -> new IllegalArgumentException("未找到指标接口定义：" + identifier));
		PreparedMetricRequest preparedRequest = prepare(definition, request);
		if (!preparedRequest.readyToExecute()) {
			return buildClarificationResult(definition, preparedRequest);
		}
		log.info("Executing metric HTTP request. apiId={}, metricName={}, method={}, path={}", definition.apiId(),
				definition.metricName(), definition.httpMethod(), definition.path());
		long startTime = System.currentTimeMillis();
		JsonNode response = executeHttp(definition, preparedRequest);
		return normalize(definition, response, System.currentTimeMillis() - startTime, preparedRequest.arguments());
	}

	private PreparedMetricRequest prepare(MetricDefinition definition, MetricQueryRequest request) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		if (request != null && request.getArguments() != null) {
			arguments.putAll(request.getArguments());
		}
		mergeLegacyArguments(arguments, request);
		mergeInferredArguments(arguments, definition, request == null ? "" : request.getQuery());
		List<MetricApiParameter> missingRequired = definition.requiredParameters()
			.stream()
			.filter(parameter -> !hasArgumentValue(arguments, parameter))
			.toList();
		return PreparedMetricRequest.builder()
			.arguments(Map.copyOf(arguments))
			.missingRequiredParameters(missingRequired)
			.readyToExecute(missingRequired.isEmpty())
			.build();
	}

	private void mergeLegacyArguments(Map<String, Object> arguments, MetricQueryRequest request) {
		if (request == null) {
			return;
		}
		putIfAbsent(arguments, "metricCode", request.getMetricCode());
		putIfAbsent(arguments, "format", request.getFormat());
		if (request.getLimit() != null) {
			arguments.putIfAbsent("limit", request.getLimit());
		}
		if (request.getGroupBy() != null && !request.getGroupBy().isEmpty()) {
			arguments.putIfAbsent("groupBy", request.getGroupBy());
		}
		if (request.getFilters() != null && !request.getFilters().isEmpty()) {
			arguments.putIfAbsent("filters", request.getFilters());
		}
		if (request.getOrderBy() != null && !request.getOrderBy().isEmpty()) {
			arguments.putIfAbsent("orderBy", request.getOrderBy());
		}
		if (request.getTimeRange() != null) {
			putIfAbsent(arguments, "start", request.getTimeRange().getStart());
			putIfAbsent(arguments, "startDate", request.getTimeRange().getStart());
			putIfAbsent(arguments, "begin", request.getTimeRange().getStart());
			putIfAbsent(arguments, "end", request.getTimeRange().getEnd());
			putIfAbsent(arguments, "endDate", request.getTimeRange().getEnd());
			putIfAbsent(arguments, "granularity", request.getTimeRange().getGranularity());
			putIfAbsent(arguments, "period", request.getTimeRange().getGranularity());
			putIfAbsent(arguments, "timezone", request.getTimeRange().getTimezone());
		}
	}

	private void mergeInferredArguments(Map<String, Object> arguments, MetricDefinition definition, String query) {
		for (MetricApiParameter parameter : definition.requestParameters()) {
			if (hasArgumentValue(arguments, parameter)) {
				continue;
			}
			Object inferred = inferArgumentValue(parameter, query);
			if (inferred != null) {
				arguments.put(parameter.name(), inferred);
				if (StringUtils.hasText(parameter.jsonPath())) {
					arguments.putIfAbsent(lastPathSegment(parameter.jsonPath()), inferred);
				}
			}
			else if (parameter.defaultValue() != null) {
				arguments.put(parameter.name(), parameter.defaultValue());
			}
		}
	}

	private Object inferArgumentValue(MetricApiParameter parameter, String query) {
		if (!StringUtils.hasText(query)) {
			return null;
		}
		String normalizedQuery = query.toLowerCase(Locale.ROOT);
		String parameterName = parameter.name().toLowerCase(Locale.ROOT);
		if (parameter.enumValues() != null) {
			for (String enumValue : parameter.enumValues()) {
				if (StringUtils.hasText(enumValue) && normalizedQuery.contains(enumValue.toLowerCase(Locale.ROOT))) {
					return enumValue;
				}
			}
		}
		if (parameterName.contains("limit") || parameterName.contains("top")) {
			java.util.regex.Matcher matcher = Pattern.compile("(top\\s*(\\d+)|前\\s*(\\d+))", Pattern.CASE_INSENSITIVE)
				.matcher(query);
			if (matcher.find()) {
				String value = firstNonBlank(matcher.group(2), matcher.group(3));
				return StringUtils.hasText(value) ? Integer.valueOf(value) : null;
			}
		}
		if (parameterName.contains("granularity") || parameterName.contains("period") || parameterName.contains("cycle")) {
			if (normalizedQuery.contains("按天") || normalizedQuery.contains("每日") || normalizedQuery.contains("天趋势")) {
				return firstMatchingEnum(parameter, List.of("DAY", "DAILY", "day"));
			}
			if (normalizedQuery.contains("按周") || normalizedQuery.contains("每周")) {
				return firstMatchingEnum(parameter, List.of("WEEK", "WEEKLY", "week"));
			}
			if (normalizedQuery.contains("按月") || normalizedQuery.contains("每月")) {
				return firstMatchingEnum(parameter, List.of("MONTH", "MONTHLY", "month"));
			}
		}
		java.util.regex.Matcher recentDays = Pattern.compile("最近\\s*(\\d+)\\s*天").matcher(query);
		if (recentDays.find() && (parameterName.contains("start") || parameterName.contains("begin") || parameterName.contains("from"))) {
			return "NOW-" + recentDays.group(1) + "D";
		}
		if (recentDays.find() && (parameterName.contains("end") || parameterName.contains("to"))) {
			return "NOW";
		}
		java.util.regex.Matcher dateMatcher = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})").matcher(query);
		if (dateMatcher.find() && (parameterName.contains("start") || parameterName.contains("begin"))) {
			return dateMatcher.group(1).replace('/', '-');
		}
		return null;
	}

	private String firstMatchingEnum(MetricApiParameter parameter, List<String> preferredValues) {
		for (String preferredValue : preferredValues) {
			for (String enumValue : parameter.enumValues()) {
				if (preferredValue.equalsIgnoreCase(enumValue)) {
					return enumValue;
				}
			}
		}
		return parameter.enumValues().isEmpty() ? null : parameter.enumValues().get(0);
	}

	private boolean hasArgumentValue(Map<String, Object> arguments, MetricApiParameter parameter) {
		for (String key : candidateArgumentKeys(parameter)) {
			Object value = arguments.get(key);
			if (value == null) {
				continue;
			}
			if (value instanceof String stringValue) {
				if (StringUtils.hasText(stringValue)) {
					return true;
				}
				continue;
			}
			return true;
		}
		return false;
	}

	private Object resolveArgumentValue(Map<String, Object> arguments, MetricApiParameter parameter) {
		for (String key : candidateArgumentKeys(parameter)) {
			Object value = arguments.get(key);
			if (value == null) {
				continue;
			}
			if (!(value instanceof String stringValue) || StringUtils.hasText(stringValue)) {
				return value;
			}
		}
		return null;
	}

	private List<String> candidateArgumentKeys(MetricApiParameter parameter) {
		Set<String> keys = new LinkedHashSet<>();
		keys.add(parameter.name());
		if (StringUtils.hasText(parameter.jsonPath())) {
			keys.add(parameter.jsonPath());
			keys.add(lastPathSegment(parameter.jsonPath()));
		}
		String lowerName = parameter.name().toLowerCase(Locale.ROOT);
		if (lowerName.contains("start") || lowerName.contains("begin")) {
			keys.addAll(List.of("start", "startDate", "begin", "from"));
		}
		if (lowerName.contains("end")) {
			keys.addAll(List.of("end", "endDate", "to"));
		}
		if (lowerName.contains("granularity") || lowerName.contains("period") || lowerName.contains("cycle")) {
			keys.addAll(List.of("granularity", "period", "cycle"));
		}
		if (lowerName.contains("timezone")) {
			keys.addAll(List.of("timezone", "timeZone", "tz"));
		}
		if (lowerName.contains("group")) {
			keys.add("groupBy");
		}
		if (lowerName.contains("filter")) {
			keys.add("filters");
		}
		if (lowerName.contains("order")) {
			keys.add("orderBy");
		}
		return List.copyOf(keys);
	}

	private String lastPathSegment(String path) {
		if (!StringUtils.hasText(path)) {
			return "";
		}
		int index = path.lastIndexOf('.');
		return index >= 0 ? path.substring(index + 1) : path;
	}

	private void putIfAbsent(Map<String, Object> arguments, String key, String value) {
		if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
			arguments.putIfAbsent(key, value.trim());
		}
	}

	private MetricQueryResult buildClarificationResult(MetricDefinition definition, PreparedMetricRequest preparedRequest) {
		List<MetricRequiredParameter> missingRequiredParameters = preparedRequest.missingRequiredParameters()
			.stream()
			.map(parameter -> MetricRequiredParameter.builder()
				.name(parameter.name())
				.location(parameter.location())
				.description(parameter.description())
				.example(parameter.example())
				.enumValues(parameter.enumValues())
				.build())
			.toList();
		String clarificationMessage = "要查询接口 `%s`，还需要补充：%s。".formatted(definition.apiId(),
				missingRequiredParameters.stream().map(MetricRequiredParameter::name).toList());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceType", "metric-system");
		metadata.put("apiId", definition.apiId());
		metadata.put("operationId", definition.operationId());
		metadata.put("path", definition.path());
		metadata.put("httpMethod", definition.httpMethod());
		metadata.put("providedArguments", preparedRequest.arguments());
		return MetricQueryResult.builder()
			.status("NEED_CLARIFICATION")
			.summary("缺少接口必填参数，暂未发起指标请求。")
			.clarificationMessage(clarificationMessage)
			.missingRequiredParameters(missingRequiredParameters)
			.metadata(metadata)
			.build();
	}

	private JsonNode executeHttp(MetricDefinition definition, PreparedMetricRequest preparedRequest) {
		if (!StringUtils.hasText(properties.getBaseUrl())) {
			throw new IllegalStateException("指标系统 baseUrl 未配置。");
		}
		try {
			HttpMethod httpMethod = HttpMethod.valueOf(definition.httpMethod());
			WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
			Map<String, Object> arguments = preparedRequest.arguments();
			String resolvedPath = resolvePath(definition.path(), definition.requestParameters(), arguments);
			ObjectNode requestBody = buildRequestBody(definition, arguments);
			var bodyUriSpec = client.method(httpMethod).uri(uriBuilder -> {
				uriBuilder.path(resolvedPath);
				appendQueryParams(uriBuilder, definition, arguments);
				return uriBuilder.build();
			});
			var requestSpec = bodyUriSpec.headers(httpHeaders -> appendHeaders(httpHeaders, definition, arguments));
			WebClient.RequestHeadersSpec<?> headersSpec = httpMethod == HttpMethod.GET ? requestSpec : requestSpec.bodyValue(requestBody);
			log.info("Calling metric system endpoint. baseUrl={}, path={}, apiId={}, arguments={}", properties.getBaseUrl(),
					resolvedPath, definition.apiId(), arguments.keySet());
			String body = headersSpec.retrieve()
				.bodyToMono(String.class)
				.timeout(Duration.ofMillis(properties.getTimeoutMs()))
				.block();
			return StringUtils.hasText(body) ? objectMapper.readTree(body) : objectMapper.createObjectNode();
		}
		catch (Exception ex) {
			throw new IllegalStateException("指标系统调用失败：" + ex.getMessage(), ex);
		}
	}

	private String resolvePath(String path, List<MetricApiParameter> parameters, Map<String, Object> arguments) {
		String resolvedPath = path;
		for (MetricApiParameter parameter : parameters) {
			if (!"path".equalsIgnoreCase(parameter.location())) {
				continue;
			}
			Object value = resolveArgumentValue(arguments, parameter);
			if (value != null) {
				resolvedPath = resolvedPath.replace("{" + parameter.name() + "}", String.valueOf(value));
			}
		}
		return resolvedPath;
	}

	private void appendQueryParams(org.springframework.web.util.UriBuilder uriBuilder, MetricDefinition definition,
			Map<String, Object> arguments) {
		for (MetricApiParameter parameter : definition.requestParameters()) {
			if (!"query".equalsIgnoreCase(parameter.location())) {
				continue;
			}
			Object value = resolveArgumentValue(arguments, parameter);
			if (value == null) {
				continue;
			}
			if (value instanceof Collection<?> collectionValue) {
				uriBuilder.queryParam(parameter.name(), collectionValue.toArray());
			}
			else {
				uriBuilder.queryParam(parameter.name(), value);
			}
		}
	}

	private void appendHeaders(org.springframework.http.HttpHeaders httpHeaders, MetricDefinition definition,
			Map<String, Object> arguments) {
		for (MetricApiParameter parameter : definition.requestParameters()) {
			if (!"header".equalsIgnoreCase(parameter.location())) {
				continue;
			}
			Object value = resolveArgumentValue(arguments, parameter);
			if (value != null) {
				httpHeaders.add(parameter.name(), String.valueOf(value));
			}
		}
	}

	private ObjectNode buildRequestBody(MetricDefinition definition, Map<String, Object> arguments) {
		ObjectNode body = objectMapper.createObjectNode();
		for (MetricApiParameter parameter : definition.requestParameters()) {
			if (!"body".equalsIgnoreCase(parameter.location())) {
				continue;
			}
			Object value = resolveArgumentValue(arguments, parameter);
			if (value == null) {
				continue;
			}
			setJsonPath(body, parameter.jsonPath(), value);
		}
		return body;
	}

	private void setJsonPath(ObjectNode root, String jsonPath, Object value) {
		if (!StringUtils.hasText(jsonPath)) {
			return;
		}
		String[] segments = jsonPath.split("\\.");
		ObjectNode current = root;
		for (int i = 0; i < segments.length - 1; i++) {
			JsonNode child = current.path(segments[i]);
			if (!(child instanceof ObjectNode)) {
				child = current.putObject(segments[i]);
			}
			current = (ObjectNode) child;
		}
		current.set(segments[segments.length - 1], objectMapper.valueToTree(value));
	}

	private MetricQueryResult normalize(MetricDefinition definition, JsonNode response, long costMs,
			Map<String, Object> arguments) {
		List<Map<String, Object>> rows = extractRows(response);
		List<MetricQueryResult.Column> columns = extractColumns(rows);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceType", "metric-system");
		metadata.put("status", "SUCCESS");
		metadata.put("apiId", definition.apiId());
		metadata.put("operationId", definition.operationId());
		metadata.put("metricCode", definition.metricCode());
		metadata.put("metricName", definition.metricName());
		metadata.put("requestArguments", arguments);
		metadata.put("costMs", costMs);
		return MetricQueryResult.builder()
			.status("SUCCESS")
			.summary("已查询接口 %s，返回 %d 行结果".formatted(definition.displayName(), rows.size()))
			.columns(columns)
			.rows(rows)
			.metadata(metadata)
			.build();
	}

	private List<Map<String, Object>> extractRows(JsonNode response) {
		JsonNode rowsNode = locateRowsNode(response);
		if (rowsNode == null || rowsNode.isNull() || rowsNode.isMissingNode()) {
			return List.of();
		}
		if (rowsNode.isArray()) {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (JsonNode row : rowsNode) {
				rows.add(toMap(row));
			}
			return rows;
		}
		return List.of(toMap(rowsNode));
	}

	private JsonNode locateRowsNode(JsonNode response) {
		if (response == null || response.isNull() || response.isMissingNode()) {
			return null;
		}
		if (response.isArray()) {
			return response;
		}
		for (String field : List.of("rows", "data", "list", "items", "result")) {
			JsonNode candidate = response.path(field);
			if (!candidate.isMissingNode() && !candidate.isNull() && (candidate.isArray() || candidate.isObject())) {
				return candidate;
			}
		}
		return response;
	}

	private List<MetricQueryResult.Column> extractColumns(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<MetricQueryResult.Column> columns = new ArrayList<>();
		rows.get(0).forEach((name, value) -> columns
			.add(MetricQueryResult.Column.builder().name(name).type(inferType(value)).description(name).build()));
		return columns;
	}

	private String inferType(Object value) {
		if (value instanceof Number) {
			return "number";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		return "string";
	}

	private Map<String, Object> toMap(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return Map.of();
		}
		if (node.isObject()) {
			return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
			});
		}
		return Map.of("value", objectMapper.convertValue(node, Object.class));
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

}

@Builder
record MetricApiParameter(String name, String location, String jsonPath, String type, boolean required,
		String description, List<String> enumValues, String example, Object defaultValue) {

	public MetricApiParameter {
		location = StringUtils.hasText(location) ? location : "body";
		jsonPath = StringUtils.hasText(jsonPath) ? jsonPath : name;
		enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
	}

}

@Builder
record PreparedMetricRequest(Map<String, Object> arguments, List<MetricApiParameter> missingRequiredParameters,
		boolean readyToExecute) {

	public PreparedMetricRequest {
		arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
		missingRequiredParameters = missingRequiredParameters == null ? List.of() : List.copyOf(missingRequiredParameters);
	}

}

@Builder
record MetricCatalogSearchCandidate(MetricDefinition definition, double score, List<String> matchedKeywords) {

	public MetricCatalogSearchCandidate {
		matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
	}

}

@Builder
record MetricDefinition(String metricCode, String metricName, List<String> aliases, String summary, String description,
		String operationId, String httpMethod, String path, List<MetricApiParameter> requestParameters,
		JsonNode requestSchema, JsonNode responseSchema, List<String> supportedGranularities,
		List<String> supportedDimensions, List<String> supportedFilters, List<String> examples, List<String> tags,
		long lastSyncTime) {

	public MetricDefinition {
		aliases = aliases == null ? List.of() : List.copyOf(aliases);
		requestParameters = requestParameters == null ? List.of() : List.copyOf(requestParameters);
		supportedGranularities = supportedGranularities == null ? List.of() : List.copyOf(supportedGranularities);
		supportedDimensions = supportedDimensions == null ? List.of() : List.copyOf(supportedDimensions);
		supportedFilters = supportedFilters == null ? List.of() : List.copyOf(supportedFilters);
		examples = examples == null ? List.of() : List.copyOf(examples);
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	public String apiId() {
		return StringUtils.hasText(operationId) ? operationId : httpMethod + " " + path;
	}

	public String displayName() {
		if (StringUtils.hasText(summary)) {
			return summary;
		}
		if (StringUtils.hasText(metricName)) {
			return metricName;
		}
		return apiId();
	}

	public List<MetricApiParameter> requiredParameters() {
		return requestParameters.stream().filter(MetricApiParameter::required).toList();
	}

}

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
class MetricQueryRequest {

	private String operationId;

	private String metricCode;

	private String query;

	private Map<String, Object> arguments;

	private TimeRange timeRange;

	private List<String> groupBy;

	private List<Map<String, Object>> filters;

	private List<Map<String, Object>> orderBy;

	private Integer limit;

	private String format;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	static class TimeRange {

		private String start;

		private String end;

		private String granularity;

		private String timezone;

	}

}

@Builder
record MetricRequiredParameter(String name, String location, String description, String example,
		List<String> enumValues) {

	public MetricRequiredParameter {
		enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
	}

}

@Builder
record MetricQueryResult(String status, String summary, String clarificationMessage,
		List<MetricRequiredParameter> missingRequiredParameters, List<Column> columns, List<Map<String, Object>> rows,
		Map<String, Object> metadata) {

	public MetricQueryResult {
		status = StringUtils.hasText(status) ? status : "SUCCESS";
		clarificationMessage = clarificationMessage == null ? "" : clarificationMessage;
		missingRequiredParameters = missingRequiredParameters == null ? List.of() : List.copyOf(missingRequiredParameters);
		columns = columns == null ? List.of() : List.copyOf(columns);
		rows = rows == null ? List.of() : List.copyOf(rows);
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	@Builder
	record Column(String name, String type, String description, String unit) {
	}

}
