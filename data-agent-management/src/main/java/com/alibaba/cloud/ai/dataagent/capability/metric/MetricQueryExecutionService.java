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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static com.alibaba.cloud.ai.dataagent.capability.metric.MetricUtils.firstNonBlank;

@Slf4j
@Service
class MetricQueryExecutionService {

	private static final Pattern TOP_N_PATTERN = Pattern.compile("(top\\s*(\\d+)|前\\s*(\\d+))",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern RECENT_DAYS_PATTERN = Pattern.compile("最近\\s*(\\d+)\\s*天");

	private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})");

	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final MetricCapabilityProperties properties;

	private final MetricCircuitBreaker circuitBreaker;

	private final ObjectMapper objectMapper;

	private final WebClient webClient;

	public MetricQueryExecutionService(MetricDefinitionLookup metricDefinitionLookup, MetricCapabilityProperties properties,
			WebClient.Builder webClientBuilder, MetricCircuitBreaker circuitBreaker, ObjectMapper objectMapper) {
		this.metricDefinitionLookup = metricDefinitionLookup;
		this.properties = properties;
		this.circuitBreaker = circuitBreaker;
		this.objectMapper = objectMapper;
		this.webClient = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
	}

	public MetricQueryResult execute(MetricQueryRequest request) {
		String identifier = request == null ? "" : firstNonBlank(request.getOperationId(), request.getMetricCode());
		if (!StringUtils.hasText(identifier)) {
			throw new IllegalArgumentException("operationId 或 metricCode 不能为空。");
		}
		MetricCatalogEntry catalogEntry = metricDefinitionLookup.getOnlineEntry(identifier)
			.orElseThrow(() -> new IllegalArgumentException("指标不存在或已下架：" + identifier));
		MetricDefinition definition = catalogEntry.definition();
		MetricApiContract contract = catalogEntry.contract();
		PreparedMetricRequest preparedRequest = prepare(contract, request);
		if (!preparedRequest.readyToExecute()) {
			return buildClarificationResult(definition, contract, preparedRequest);
		}
		if (!circuitBreaker.allowRequest()) {
			log.warn("Metric circuit breaker blocked request. apiId={}", contract.apiId());
			return buildCircuitBreakerResult(contract, preparedRequest);
		}
		log.info("Executing metric HTTP request. apiId={}, metricName={}, method={}, path={}, argumentKeys={}",
				contract.apiId(), definition.metricName(), contract.httpMethod(), contract.path(),
				preparedRequest.arguments().keySet());
		long startTime = System.currentTimeMillis();
		try {
			JsonNode response = executeHttp(contract, preparedRequest);
			circuitBreaker.recordSuccess();
			long costMs = System.currentTimeMillis() - startTime;
			MetricQueryResult result = normalize(definition, contract, response, costMs, preparedRequest.arguments());
			log.info("Metric query execute success. apiId={}, costMs={}ms, rowCount={}",
					contract.apiId(), costMs, result.rows() != null ? result.rows().size() : 0);
			return result;
		}
		catch (Exception ex) {
			circuitBreaker.recordFailure();
			log.warn("Metric query execution failed, returning FALLBACK_TO_DB. apiId={}", contract.apiId(), ex);
			return buildFallbackResult(contract, preparedRequest, ex);
		}
	}

	private MetricQueryResult buildFallbackResult(MetricApiContract contract,
			PreparedMetricRequest preparedRequest, Exception ex) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceType", "metric-system");
		metadata.put("status", "HTTP_ERROR");
		metadata.put("apiId", contract.apiId());
		metadata.put("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
		return MetricQueryResult.builder()
			.status("FALLBACK_TO_DB")
			.summary("指标系统调用失败，建议使用数据库查询作为降级方案。")
			.metadata(metadata)
			.build();
	}

	private MetricQueryResult buildCircuitBreakerResult(MetricApiContract contract,
			PreparedMetricRequest preparedRequest) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceType", "metric-system");
		metadata.put("status", "CIRCUIT_OPEN");
		metadata.put("apiId", contract.apiId());
		metadata.put("circuitBreakerState", circuitBreaker.getState().name());
		return MetricQueryResult.builder()
			.status("FALLBACK_TO_DB")
			.summary("指标系统当前不可用（熔断保护中），建议使用数据库查询作为降级方案。")
			.metadata(metadata)
			.build();
	}

	private PreparedMetricRequest prepare(MetricApiContract contract, MetricQueryRequest request) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		if (request != null && request.getArguments() != null) {
			arguments.putAll(request.getArguments());
		}
		mergeLegacyArguments(arguments, request);
		mergeInferredArguments(arguments, contract, request == null ? "" : request.getQuery());
		List<MetricApiParameter> missingRequired = contract.requiredParameters()
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

	private void mergeInferredArguments(Map<String, Object> arguments, MetricApiContract contract, String query) {
		for (MetricApiParameter parameter : contract.requestParameters()) {
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
			Matcher matcher = TOP_N_PATTERN.matcher(query);
			if (matcher.find()) {
				String value = firstNonBlank(matcher.group(2), matcher.group(3));
				return StringUtils.hasText(value) ? Integer.valueOf(value) : null;
			}
		}
		if (parameterName.contains("granularity") || parameterName.contains("period")
				|| parameterName.contains("cycle")) {
			if (normalizedQuery.contains("按天") || normalizedQuery.contains("每日")
					|| normalizedQuery.contains("天趋势")) {
				return firstMatchingEnum(parameter, List.of("DAY", "DAILY", "day"));
			}
			if (normalizedQuery.contains("按周") || normalizedQuery.contains("每周")) {
				return firstMatchingEnum(parameter, List.of("WEEK", "WEEKLY", "week"));
			}
			if (normalizedQuery.contains("按月") || normalizedQuery.contains("每月")) {
				return firstMatchingEnum(parameter, List.of("MONTH", "MONTHLY", "month"));
			}
		}
		Matcher recentDays = RECENT_DAYS_PATTERN.matcher(query);
		if (recentDays.find()) {
			int days = Integer.parseInt(recentDays.group(1));
			LocalDate today = LocalDate.now();
			if (parameterName.contains("start") || parameterName.contains("begin")
					|| parameterName.contains("from")) {
				return today.minusDays(days).format(DATE_FORMATTER);
			}
			if (parameterName.contains("end") || parameterName.contains("to")) {
				return today.format(DATE_FORMATTER);
			}
		}
		Matcher dateMatcher = DATE_PATTERN.matcher(query);
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

	private MetricQueryResult buildClarificationResult(MetricDefinition definition, MetricApiContract contract,
			PreparedMetricRequest preparedRequest) {
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
		String clarificationMessage = "要查询接口 `%s`，还需要补充：%s。".formatted(contract.apiId(),
				missingRequiredParameters.stream().map(MetricRequiredParameter::name).toList());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceType", "metric-system");
		metadata.put("apiId", contract.apiId());
		metadata.put("operationId", contract.operationId());
		metadata.put("path", contract.path());
		metadata.put("httpMethod", contract.httpMethod());
		metadata.put("providedArguments", preparedRequest.arguments());
		return MetricQueryResult.builder()
			.status("NEED_CLARIFICATION")
			.summary("缺少接口必填参数，暂未发起指标请求。")
			.clarificationMessage(clarificationMessage)
			.missingRequiredParameters(missingRequiredParameters)
			.metadata(metadata)
			.build();
	}

	private JsonNode executeHttp(MetricApiContract contract, PreparedMetricRequest preparedRequest) {
		if (!StringUtils.hasText(properties.getBaseUrl())) {
			throw new IllegalStateException("指标系统 baseUrl 未配置。");
		}
		try {
			HttpMethod httpMethod = HttpMethod.valueOf(contract.httpMethod());
			Map<String, Object> arguments = preparedRequest.arguments();
			String resolvedPath = resolvePath(contract.path(), contract.requestParameters(), arguments);
			ObjectNode requestBody = buildRequestBody(contract, arguments);
			var bodyUriSpec = webClient.method(httpMethod).uri(uriBuilder -> {
				uriBuilder.path(resolvedPath);
				appendQueryParams(uriBuilder, contract, arguments);
				return uriBuilder.build();
			});
			var requestSpec = bodyUriSpec
				.headers(httpHeaders -> appendHeaders(httpHeaders, contract, arguments));
			WebClient.RequestHeadersSpec<?> headersSpec = httpMethod == HttpMethod.GET ? requestSpec
					: requestSpec.bodyValue(requestBody);
			log.info("Calling metric system endpoint. baseUrl={}, path={}, apiId={}, params={}, body={}",
					properties.getBaseUrl(), resolvedPath, contract.apiId(), arguments.keySet(), requestBody.size());
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

	private void appendQueryParams(org.springframework.web.util.UriBuilder uriBuilder, MetricApiContract contract,
			Map<String, Object> arguments) {
		for (MetricApiParameter parameter : contract.requestParameters()) {
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

	private void appendHeaders(org.springframework.http.HttpHeaders httpHeaders, MetricApiContract contract,
			Map<String, Object> arguments) {
		for (MetricApiParameter parameter : contract.requestParameters()) {
			if (!"header".equalsIgnoreCase(parameter.location())) {
				continue;
			}
			Object value = resolveArgumentValue(arguments, parameter);
			if (value != null) {
				httpHeaders.add(parameter.name(), String.valueOf(value));
			}
		}
	}

	private ObjectNode buildRequestBody(MetricApiContract contract, Map<String, Object> arguments) {
		ObjectNode body = objectMapper.createObjectNode();
		for (MetricApiParameter parameter : contract.requestParameters()) {
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

	private MetricQueryResult normalize(MetricDefinition definition, MetricApiContract contract, JsonNode response, long costMs,
			Map<String, Object> arguments) {
		List<Map<String, Object>> rows = extractRows(response);
		List<MetricQueryResult.Column> columns = extractColumns(rows);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("sourceType", "metric-system");
		metadata.put("status", "SUCCESS");
		metadata.put("apiId", contract.apiId());
		metadata.put("operationId", contract.operationId());
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
		rows.get(0)
			.forEach((name, value) -> columns.add(MetricQueryResult.Column.builder()
				.name(name)
				.type(inferType(value))
				.description(name)
				.build()));
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

}
