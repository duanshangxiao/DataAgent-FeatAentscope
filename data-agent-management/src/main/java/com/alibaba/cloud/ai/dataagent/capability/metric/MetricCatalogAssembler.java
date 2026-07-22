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

import com.alibaba.cloud.ai.dataagent.entity.MetricLocalConfig;
import com.alibaba.cloud.ai.dataagent.mapper.MetricLocalConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Applies durable local semantic corrections without mutating imported OpenAPI data. */
@Slf4j
@Component
@RequiredArgsConstructor
class MetricCatalogAssembler {

	private final MetricLocalConfigMapper localConfigMapper;

	private final ObjectMapper objectMapper;

	List<MetricCatalogEntry> assemble(ParsedMetricCatalog sourceCatalog) {
		Map<String, MetricLocalConfig> configs = new LinkedHashMap<>();
		for (MetricLocalConfig config : localConfigMapper.selectAll()) {
			configs.put(config.getMetricKey(), config);
		}
		List<MetricCatalogEntry> result = new ArrayList<>();
		for (MetricCatalogEntry sourceEntry : sourceCatalog.entries()) {
			MetricLocalConfig config = configs.get(sourceEntry.definition().metricKey());
			result.add(config == null ? sourceEntry : apply(sourceEntry, config));
		}
		return List.copyOf(result);
	}

	private MetricCatalogEntry apply(MetricCatalogEntry sourceEntry, MetricLocalConfig config) {
		MetricDefinition source = sourceEntry.definition();
		List<String> aliases = new ArrayList<>(source.aliases());
		aliases.addAll(readAliases(config.getLocalAliases()));
		aliases = new ArrayList<>(new LinkedHashSet<>(aliases));
		MetricDefinition effective = MetricDefinition.builder()
			.metricKey(source.metricKey())
			.metricCode(source.metricCode())
			.metricName(valueOrFallback(config.getLocalMetricName(), source.metricName()))
			.aliases(aliases)
			.summary(source.summary())
			.description(valueOrFallback(config.getLocalDescription(), source.description()))
			.supportedGranularities(source.supportedGranularities())
			.supportedDimensions(source.supportedDimensions())
			.supportedFilters(source.supportedFilters())
			.examples(source.examples())
			.tags(source.tags())
			.lastSyncTime(source.lastSyncTime())
			.build();
		MetricServiceStatus status = parseStatus(config.getServiceStatus());
		boolean hasOverride = StringUtils.hasText(config.getLocalMetricName())
				|| StringUtils.hasText(config.getLocalDescription()) || !readAliases(config.getLocalAliases()).isEmpty();
		return new MetricCatalogEntry(effective, sourceEntry.contract(), sourceEntry.binding(), status, hasOverride,
				config.getIndexStatus(), config.getLastError());
	}

	private List<String> readAliases(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(value, new TypeReference<List<String>>() {
			}).stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
		}
		catch (Exception ex) {
			log.warn("Ignoring invalid local metric aliases JSON.");
			return List.of();
		}
	}

	private MetricServiceStatus parseStatus(String value) {
		try {
			return StringUtils.hasText(value) ? MetricServiceStatus.valueOf(value) : MetricServiceStatus.ONLINE;
		}
		catch (IllegalArgumentException ex) {
			return MetricServiceStatus.ONLINE;
		}
	}

	private String valueOrFallback(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

}
