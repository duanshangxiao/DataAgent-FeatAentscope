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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.capability.metric.MetricCapabilityStatus;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricCatalogAdminService;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricCatalogEntry;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricCatalogQueryService;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricDefinition;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricDefinitionLookup;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricOpenApiSyncService;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricRetrievalService;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricSearchCommand;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricSearchResult;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricServiceStatus;
import com.alibaba.cloud.ai.dataagent.dto.metric.MetricCatalogView;
import com.alibaba.cloud.ai.dataagent.dto.metric.MetricOverrideRequest;
import com.alibaba.cloud.ai.dataagent.dto.metric.MetricServiceStatusRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/metric-capability")
@RequiredArgsConstructor
public class MetricCapabilityController {

	private final MetricCapabilityStatus metricCapabilityStatus;

	private final MetricOpenApiSyncService metricOpenApiSyncService;

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final MetricCatalogQueryService metricCatalogQueryService;

	private final MetricCatalogAdminService metricCatalogAdminService;

	private final MetricRetrievalService metricRetrievalService;

	private static final String STATE_READY = "ready";

	private static final String STATE_NOT_READY = "not_ready";

	@GetMapping("/status")
	public ResponseEntity<Map<String, Object>> status() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ready", metricCapabilityStatus.isReady());
		result.put("definitionCount", metricCapabilityStatus.getDefinitionCount());
		result.put("metricCount", metricDefinitionLookup.listEntries().size());
		result.put("onlineCount", metricDefinitionLookup.listEntries().stream().filter(MetricCatalogEntry::online).count());
		result.put("offlineCount", metricDefinitionLookup.listEntries().stream().filter(entry -> !entry.online()).count());
		result.put("overrideCount", metricDefinitionLookup.listEntries()
			.stream()
			.filter(MetricCatalogEntry::hasLocalOverride)
			.count());
		result.put("generation", metricDefinitionLookup.generation());
		result.put("state", metricCapabilityStatus.isReady() ? STATE_READY : STATE_NOT_READY);
		result.put("lastRefreshSuccessTime", metricCapabilityStatus.getLastRefreshSuccessTime());
		if (metricCapabilityStatus.getLastRefreshSuccessTime() > 0) {
			long secondsAgo = Duration
				.ofMillis(System.currentTimeMillis() - metricCapabilityStatus.getLastRefreshSuccessTime())
				.toSeconds();
			result.put("lastRefreshSuccessSecondsAgo", secondsAgo);
		}
		result.put("lastRefreshFailureTime", metricCapabilityStatus.getLastRefreshFailureTime());
		result.put("lastRefreshError", metricCapabilityStatus.getLastRefreshError());
		result.put("circuitBreakerState", metricCapabilityStatus.getCircuitBreakerState());
		return ResponseEntity.ok(result);
	}

	@GetMapping("/definitions")
	public ResponseEntity<List<MetricDefinition>> definitions() {
		return ResponseEntity.ok(metricDefinitionLookup.listAll());
	}

	@GetMapping("/metrics")
	public ResponseEntity<List<MetricCatalogView>> metrics() {
		return ResponseEntity.ok(metricCatalogQueryService.list());
	}

	@GetMapping("/metrics/{metricKey}")
	public ResponseEntity<MetricCatalogView> metric(@PathVariable String metricKey) {
		return ResponseEntity.ok(metricCatalogQueryService.get(metricKey));
	}

	@PutMapping("/metrics/{metricKey}/override")
	public ResponseEntity<MetricCatalogView> saveOverride(@PathVariable String metricKey,
			@RequestBody MetricOverrideRequest request) {
		metricCatalogAdminService.saveOverride(metricKey, request);
		return ResponseEntity.ok(metricCatalogQueryService.get(metricKey));
	}

	@DeleteMapping("/metrics/{metricKey}/override")
	public ResponseEntity<MetricCatalogView> clearOverride(@PathVariable String metricKey) {
		metricCatalogAdminService.clearOverride(metricKey);
		return ResponseEntity.ok(metricCatalogQueryService.get(metricKey));
	}

	@PutMapping("/metrics/{metricKey}/service-status")
	public ResponseEntity<MetricCatalogView> updateServiceStatus(@PathVariable String metricKey,
			@Valid @RequestBody MetricServiceStatusRequest request) {
		MetricServiceStatus status;
		try {
			status = MetricServiceStatus.valueOf(request.status().trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("指标服务状态只支持 ONLINE 或 OFFLINE。");
		}
		metricCatalogAdminService.updateServiceStatus(metricKey, status);
		return ResponseEntity.ok(metricCatalogQueryService.get(metricKey));
	}

	@PostMapping("/search")
	public ResponseEntity<MetricSearchResult> search(@RequestBody MetricSearchCommand command) {
		return ResponseEntity.ok(metricRetrievalService.search(command));
	}

	@PostMapping("/refresh")
	public ResponseEntity<Map<String, Object>> refresh() {
		CompletableFuture.runAsync(() -> {
			try {
				metricOpenApiSyncService.refreshCatalog();
			}
			catch (Exception ex) {
				log.warn("Manual metric catalog refresh failed.", ex);
			}
		});
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("success", true);
		result.put("message", "刷新已触发，可通过 GET /status 查看进度");
		return ResponseEntity.ok(result);
	}

}
