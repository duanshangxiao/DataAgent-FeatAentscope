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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Atomic runtime snapshot shared by search, describe and execute. */
@Component
public class MetricDefinitionLookup {

	private final AtomicReference<CatalogSnapshot> snapshotRef = new AtomicReference<>(CatalogSnapshot.empty());

	void refresh(Iterable<MetricCatalogEntry> entries, String generation) {
		Map<String, MetricCatalogEntry> byKey = new LinkedHashMap<>();
		Map<String, String> identifierToKey = new LinkedHashMap<>();
		if (entries != null) {
			for (MetricCatalogEntry entry : entries) {
				if (entry == null || entry.definition() == null
						|| !StringUtils.hasText(entry.definition().metricKey())) {
					continue;
				}
				String metricKey = entry.definition().metricKey();
				if (byKey.put(metricKey, entry) != null) {
					throw new IllegalArgumentException("重复的指标 metricKey：" + metricKey);
				}
				register(identifierToKey, metricKey, metricKey);
				register(identifierToKey, entry.definition().metricCode(), metricKey);
				register(identifierToKey, entry.contract().operationId(), metricKey);
				register(identifierToKey, entry.contract().apiId(), metricKey);
				register(identifierToKey, entry.contract().path(), metricKey);
			}
		}
		snapshotRef.set(new CatalogSnapshot(generation == null ? "" : generation, Map.copyOf(byKey),
				Map.copyOf(identifierToKey)));
	}

	private void register(Map<String, String> index, String identifier, String metricKey) {
		if (StringUtils.hasText(identifier)) {
			index.putIfAbsent(identifier.trim().toLowerCase(Locale.ROOT), metricKey);
		}
	}

	public Optional<MetricCatalogEntry> getEntry(String identifier) {
		if (!StringUtils.hasText(identifier)) {
			return Optional.empty();
		}
		CatalogSnapshot snapshot = snapshotRef.get();
		String metricKey = snapshot.identifierToKey().get(identifier.trim().toLowerCase(Locale.ROOT));
		return Optional.ofNullable(metricKey == null ? null : snapshot.entriesByKey().get(metricKey));
	}

	public Optional<MetricCatalogEntry> getOnlineEntry(String identifier) {
		return getEntry(identifier).filter(MetricCatalogEntry::online);
	}

	Optional<MetricDefinition> get(String identifier) {
		return getOnlineEntry(identifier).map(MetricCatalogEntry::definition);
	}

	public List<MetricCatalogEntry> listEntries() {
		return new ArrayList<>(snapshotRef.get().entriesByKey().values());
	}

	public List<MetricDefinition> listAll() {
		return listEntries().stream().map(MetricCatalogEntry::definition).toList();
	}

	public String generation() {
		return snapshotRef.get().generation();
	}

	void updateRuntimeStatus(String metricKey, MetricServiceStatus status) {
		CatalogSnapshot current = snapshotRef.get();
		MetricCatalogEntry existing = current.entriesByKey().get(metricKey);
		if (existing == null) {
			throw new IllegalArgumentException("未找到指标：" + metricKey);
		}
		Map<String, MetricCatalogEntry> updated = new LinkedHashMap<>(current.entriesByKey());
		updated.put(metricKey, new MetricCatalogEntry(existing.definition(), existing.contract(), existing.binding(),
				status, existing.hasLocalOverride(), existing.indexStatus(), existing.indexError()));
		snapshotRef.set(new CatalogSnapshot(current.generation(), Map.copyOf(updated), current.identifierToKey()));
	}

	boolean isAvailable() {
		return snapshotRef.get().entriesByKey().values().stream().anyMatch(MetricCatalogEntry::online);
	}

	int size() {
		return (int) snapshotRef.get().entriesByKey().values().stream().filter(MetricCatalogEntry::online).count();
	}

	private record CatalogSnapshot(String generation, Map<String, MetricCatalogEntry> entriesByKey,
			Map<String, String> identifierToKey) {

		private static CatalogSnapshot empty() {
			return new CatalogSnapshot("", Map.of(), Map.of());
		}

	}

}
