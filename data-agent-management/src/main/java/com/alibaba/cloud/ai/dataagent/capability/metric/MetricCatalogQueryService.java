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

import com.alibaba.cloud.ai.dataagent.dto.metric.MetricCatalogView;
import com.alibaba.cloud.ai.dataagent.entity.MetricLocalConfig;
import com.alibaba.cloud.ai.dataagent.mapper.MetricLocalConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MetricCatalogQueryService {

	private final MetricDefinitionLookup lookup;

	private final MetricOpenApiSyncService syncService;

	private final MetricLocalConfigMapper localConfigMapper;

	private final ObjectMapper objectMapper;

	public List<MetricCatalogView> list() {
		return lookup.listEntries().stream().map(this::toView).toList();
	}

	public MetricCatalogView get(String metricKey) {
		return lookup.getEntry(metricKey).map(this::toView)
			.orElseThrow(() -> new IllegalArgumentException("未找到指标：" + metricKey));
	}

	private MetricCatalogView toView(MetricCatalogEntry effectiveEntry) {
		MetricCatalogEntry sourceEntry = syncService.sourceCatalog()
			.entries()
			.stream()
			.filter(entry -> effectiveEntry.definition().metricKey().equals(entry.definition().metricKey()))
			.findFirst()
			.orElse(effectiveEntry);
		MetricLocalConfig config = localConfigMapper.selectByMetricKey(effectiveEntry.definition().metricKey());
		MetricDefinition effective = effectiveEntry.definition();
		MetricDefinition source = sourceEntry.definition();
		return new MetricCatalogView(effective.metricKey(), effective.metricCode(), effective.metricName(),
				effective.description(), effective.aliases(), source.metricName(), source.description(), source.aliases(),
				config == null ? null : config.getLocalMetricName(),
				config == null ? null : config.getLocalDescription(),
				config == null ? List.of() : readAliases(config.getLocalAliases()), effectiveEntry.serviceStatus().name(),
				effectiveEntry.indexStatus(), effectiveEntry.indexError(), effectiveEntry.hasLocalOverride(),
				effectiveEntry.contract());
	}

	private List<String> readAliases(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(value, new TypeReference<List<String>>() {
			});
		}
		catch (Exception ex) {
			return List.of();
		}
	}

}
