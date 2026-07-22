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

import com.alibaba.cloud.ai.dataagent.dto.metric.MetricOverrideRequest;
import com.alibaba.cloud.ai.dataagent.entity.MetricLocalConfig;
import com.alibaba.cloud.ai.dataagent.mapper.MetricLocalConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MetricCatalogAdminService {

	private final MetricLocalConfigMapper localConfigMapper;

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final MetricOpenApiSyncService syncService;

	private final ObjectMapper objectMapper;

	public MetricCatalogEntry saveOverride(String metricKey, MetricOverrideRequest request) {
		requireMetric(metricKey);
		MetricLocalConfig config = ensureConfig(metricKey);
		config.setLocalMetricName(trimToNull(request == null ? null : request.localMetricName()));
		config.setLocalDescription(trimToNull(request == null ? null : request.localDescription()));
		try {
			List<String> aliases = request == null || request.localAliases() == null ? List.of()
					: request.localAliases().stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
			config.setLocalAliases(aliases.isEmpty() ? null : objectMapper.writeValueAsString(aliases));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("本地指标别名格式不正确。", ex);
		}
		localConfigMapper.updateOverride(config);
		syncService.rebuildFromLocalConfig();
		return requireMetric(metricKey);
	}

	public MetricCatalogEntry clearOverride(String metricKey) {
		requireMetric(metricKey);
		ensureConfig(metricKey);
		localConfigMapper.clearOverride(metricKey);
		syncService.rebuildFromLocalConfig();
		return requireMetric(metricKey);
	}

	public MetricCatalogEntry updateServiceStatus(String metricKey, MetricServiceStatus status) {
		MetricCatalogEntry before = requireMetric(metricKey);
		ensureConfig(metricKey);
		if (status == MetricServiceStatus.OFFLINE) {
			metricDefinitionLookup.updateRuntimeStatus(metricKey, MetricServiceStatus.OFFLINE);
			try {
				localConfigMapper.updateServiceStatus(metricKey, status.name());
			}
			catch (RuntimeException ex) {
				metricDefinitionLookup.updateRuntimeStatus(metricKey, before.serviceStatus());
				throw ex;
			}
		}
		else {
			localConfigMapper.updateServiceStatus(metricKey, status.name());
		}
		syncService.rebuildFromLocalConfig();
		return requireMetric(metricKey);
	}

	private MetricCatalogEntry requireMetric(String metricKey) {
		return metricDefinitionLookup.getEntry(metricKey)
			.orElseThrow(() -> new IllegalArgumentException("未找到指标：" + metricKey));
	}

	private MetricLocalConfig ensureConfig(String metricKey) {
		MetricLocalConfig config = localConfigMapper.selectByMetricKey(metricKey);
		if (config == null) {
			localConfigMapper.insertDefault(metricKey);
			config = localConfigMapper.selectByMetricKey(metricKey);
		}
		return config;
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
