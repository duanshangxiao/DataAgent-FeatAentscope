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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Common post-parse gate shared by OpenAPI and standard-catalog adapters. */
@Component
public class MetricCatalogValidator {

	private static final Set<String> METHODS = Set.of("GET", "POST");

	private static final Set<String> LOCATIONS = Set.of("path", "query", "header", "body");

	private static final Set<String> DYNAMIC_METRIC_SELECTORS = Set.of("metriccode", "metricid", "indicatorcode",
			"indicatorid", "metricname", "indicatorname");

	private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");

	public void validate(ParsedMetricCatalog catalog) {
		if (catalog == null || catalog.entries().isEmpty()) {
			throw new IllegalArgumentException("Metric catalog must contain at least one metric.");
		}
		Set<String> metricKeys = new HashSet<>();
		Set<String> metricCodes = new HashSet<>();
		Set<String> operationIds = new HashSet<>();
		Set<String> paths = new HashSet<>();
		for (int index = 0; index < catalog.entries().size(); index++) {
			validateEntry(catalog.entries().get(index), index, metricKeys, metricCodes, operationIds, paths);
		}
	}

	private void validateEntry(MetricCatalogEntry entry, int index, Set<String> metricKeys, Set<String> metricCodes,
			Set<String> operationIds, Set<String> paths) {
		if (entry == null || entry.definition() == null || entry.contract() == null || entry.binding() == null) {
			throw invalid(index, "definition, invocation and binding must all exist");
		}
		MetricDefinition definition = entry.definition();
		MetricApiContract contract = entry.contract();
		requireUnique(definition.metricKey(), metricKeys, index, "metricKey");
		requireText(definition.metricName(), index, "metricName");
		if (StringUtils.hasText(definition.metricCode())) {
			requireUnique(definition.metricCode(), metricCodes, index, "metricCode");
		}
		requireUnique(contract.operationId(), operationIds, index, "operationId");
		requireUnique(contract.path(), paths, index, "path");
		if (!contract.path().startsWith("/") || contract.path().contains("://") || contract.path().contains("?")) {
			throw invalid(index, "path must be a fixed relative path without protocol or query string: "
					+ contract.path());
		}
		String method = StringUtils.hasText(contract.httpMethod())
				? contract.httpMethod().toUpperCase(Locale.ROOT) : "";
		if (!METHODS.contains(method)) {
			throw invalid(index, "method must be GET or POST: " + contract.httpMethod());
		}
		Set<String> requiredPathParameters = new HashSet<>();
		for (MetricApiParameter parameter : contract.requestParameters()) {
			validateParameter(parameter, index);
			if (DYNAMIC_METRIC_SELECTORS.contains(parameter.name().toLowerCase(Locale.ROOT))) {
				throw invalid(index, "dynamic metric selector parameter is forbidden: " + parameter.name());
			}
			if ("GET".equals(method) && "body".equalsIgnoreCase(parameter.location())) {
				throw invalid(index, "GET invocation must not declare body parameter: " + parameter.name());
			}
			if ("body".equalsIgnoreCase(parameter.location())
					&& (contract.requestSchema().isNull() || !contract.requestSchema().isObject())) {
				throw invalid(index, "POST body parameters require an object requestSchema");
			}
			if ("path".equalsIgnoreCase(parameter.location()) && parameter.required()) {
				requiredPathParameters.add(parameter.name());
			}
		}
		Matcher matcher = PATH_PARAMETER.matcher(contract.path());
		while (matcher.find()) {
			String placeholder = matcher.group(1);
			if (DYNAMIC_METRIC_SELECTORS.contains(placeholder.toLowerCase(Locale.ROOT))) {
				throw invalid(index, "dynamic metric selector path placeholder is forbidden: " + placeholder);
			}
			if (!requiredPathParameters.contains(placeholder)) {
				throw invalid(index, "path placeholder requires a matching required path parameter: " + placeholder);
			}
		}
		MetricApiResponse response = contract.response();
		if ((response.schema() == null || response.schema().isNull() || response.schema().isMissingNode())
				&& response.examples().isEmpty()) {
			throw invalid(index, "response schema or success example is required");
		}
		if (!definition.metricKey().equals(entry.binding().metricKey())
				|| !contract.operationId().equals(entry.binding().operationId())) {
			throw invalid(index, "binding must match metricKey and operationId");
		}
		validateResponseHints(response, index);
	}

	private void validateParameter(MetricApiParameter parameter, int index) {
		if (parameter == null) {
			throw invalid(index, "parameter must not be null");
		}
		requireText(parameter.name(), index, "parameter.name");
		requireText(parameter.location(), index, "parameter.location");
		requireText(parameter.type(), index, "parameter.type");
		if (!LOCATIONS.contains(parameter.location().toLowerCase(Locale.ROOT))) {
			throw invalid(index, "unsupported parameter location: " + parameter.location());
		}
	}

	private void validateResponseHints(MetricApiResponse response, int index) {
		MetricSuccessCriteria criteria = response.successCriteria();
		if (criteria != null) {
			if (!isSimpleJsonPath(criteria.jsonPath())) {
				throw invalid(index, "successCriteria.jsonPath must be a simple object path");
			}
			if (!"EQ".equals(criteria.operator())) {
				throw invalid(index, "successCriteria.operator v1 only supports EQ");
			}
		}
		if (StringUtils.hasText(response.resultPath()) && !isSimpleJsonPath(response.resultPath())) {
			throw invalid(index, "resultPath must be a simple object path");
		}
		if (StringUtils.hasText(response.messagePath()) && !isSimpleJsonPath(response.messagePath())) {
			throw invalid(index, "messagePath must be a simple object path");
		}
	}

	private boolean isSimpleJsonPath(String path) {
		return StringUtils.hasText(path) && path.matches("^\\$?(?:\\.[A-Za-z0-9_-]+)+$");
	}

	private void requireUnique(String value, Set<String> values, int index, String field) {
		requireText(value, index, field);
		if (!values.add(value)) {
			throw invalid(index, "duplicate " + field + ": " + value);
		}
	}

	private void requireText(String value, int index, String field) {
		if (!StringUtils.hasText(value)) {
			throw invalid(index, field + " must not be blank");
		}
	}

	private IllegalArgumentException invalid(int index, String message) {
		return new IllegalArgumentException("Invalid metric catalog entry[" + index + "]: " + message);
	}

}
