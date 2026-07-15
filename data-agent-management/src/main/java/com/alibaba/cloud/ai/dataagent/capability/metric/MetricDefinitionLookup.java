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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MetricDefinitionLookup {

	private final AtomicReference<Map<String, MetricDefinition>> definitionsRef = new AtomicReference<>(Map.of());

	void refresh(Iterable<MetricDefinition> definitions) {
		Map<String, MetricDefinition> map = new LinkedHashMap<>();
		if (definitions != null) {
			for (MetricDefinition definition : definitions) {
				if (definition != null && StringUtils.hasText(definition.apiId())) {
					map.put(definition.apiId(), definition);
				}
			}
		}
		definitionsRef.set(Map.copyOf(map));
	}

	Optional<MetricDefinition> get(String identifier) {
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

	public List<MetricDefinition> listAll() {
		return new ArrayList<>(definitionsRef.get().values());
	}

	boolean isAvailable() {
		return !definitionsRef.get().isEmpty();
	}

	int size() {
		return definitionsRef.get().size();
	}

}
