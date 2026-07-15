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
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricOpenApiSyncService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/metric-capability")
@RequiredArgsConstructor
public class MetricCapabilityController {

	private final MetricCapabilityStatus metricCapabilityStatus;

	private final MetricOpenApiSyncService metricOpenApiSyncService;

	private static final String STATE_READY = "ready";

	private static final String STATE_NOT_READY = "not_ready";

	@GetMapping("/status")
	public ResponseEntity<Map<String, Object>> status() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ready", metricCapabilityStatus.isReady());
		result.put("definitionCount", metricCapabilityStatus.getDefinitionCount());
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
