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

import java.util.List;
import lombok.Builder;
import org.springframework.util.StringUtils;

@Builder
public record MetricDefinition(String metricKey, String metricCode, String metricName, List<String> aliases,
		String summary, String description, List<String> supportedGranularities,
		List<String> supportedDimensions, List<String> supportedFilters, List<String> examples, List<String> tags,
		long lastSyncTime) {

	public MetricDefinition {
		aliases = aliases == null ? List.of() : List.copyOf(aliases);
		supportedGranularities = supportedGranularities == null ? List.of() : List.copyOf(supportedGranularities);
		supportedDimensions = supportedDimensions == null ? List.of() : List.copyOf(supportedDimensions);
		supportedFilters = supportedFilters == null ? List.of() : List.copyOf(supportedFilters);
		examples = examples == null ? List.of() : List.copyOf(examples);
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	public String displayName() {
		if (StringUtils.hasText(summary)) {
			return summary;
		}
		if (StringUtils.hasText(metricName)) {
			return metricName;
		}
		return StringUtils.hasText(metricCode) ? metricCode : metricKey;
	}

}
