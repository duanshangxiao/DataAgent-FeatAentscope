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
package com.alibaba.cloud.ai.dataagent.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRoutingServiceTest {

	@Test
	void route_prefersHigherScoreMetricProvider() {
		CapabilityProvider metricProvider = new FixedCapabilityProvider("metric-system",
				CapabilityRouteResult.builder()
					.routeType(CapabilityRouteType.METRIC_ONLY)
					.score(0.92D)
					.matchedCapabilityId("metric-system")
					.matchedTargets(List.of("gmv"))
					.reason("hit metric")
					.build());
		CapabilityProvider dbProvider = new FixedCapabilityProvider("other", CapabilityRouteResult.dbOnly("db"));
		CapabilityRoutingService service = new CapabilityRoutingService(new CapabilityRegistry(List.of(metricProvider, dbProvider)));

		CapabilityRouteResult result = service.route("1", "最近7天GMV趋势");

		assertEquals(CapabilityRouteType.METRIC_ONLY, result.routeType());
		assertEquals("metric-system", result.matchedCapabilityId());
		assertEquals(List.of("gmv"), result.matchedTargets());
	}

	@Test
	void buildRoutedToolCallbacks_filtersDatabaseToolsWhenMetricOnly() {
		Map<String, ToolCallback> baseTools = new LinkedHashMap<>();
		baseTools.put("datasource.demo.search", new NamedToolCallback("datasource.demo.search"));
		baseTools.put("sql_guard.check", new NamedToolCallback("sql_guard.check"));
		baseTools.put("domain_business_knowledge.search", new NamedToolCallback("domain_business_knowledge.search"));
		CapabilityProvider metricProvider = new CapabilityProvider() {
			@Override
			public String capabilityId() {
				return "metric-system";
			}

			@Override
			public boolean enabledForAgent(String agentId) {
				return true;
			}

			@Override
			public CapabilityRouteResult route(String agentId, String query) {
				return null;
			}

			@Override
			public Map<String, ToolCallback> getToolCallbacks(String agentId) {
				return Map.of("metric.catalog.search", new NamedToolCallback("metric.catalog.search"));
			}

			@Override
			public void refreshMetadata() {
			}
		};
		CapabilityRoutingService service = new CapabilityRoutingService(new CapabilityRegistry(List.of(metricProvider)));
		CapabilityRouteResult routeResult = CapabilityRouteResult.builder()
			.routeType(CapabilityRouteType.METRIC_ONLY)
			.score(0.9D)
			.matchedCapabilityId("metric-system")
			.matchedTargets(List.of("gmv"))
			.reason("metric only")
			.build();

		Map<String, ToolCallback> routed = service.buildRoutedToolCallbacks("1", routeResult, baseTools);

		assertFalse(routed.containsKey("datasource.demo.search"));
		assertFalse(routed.containsKey("sql_guard.check"));
		assertTrue(routed.containsKey("domain_business_knowledge.search"));
		assertTrue(routed.containsKey("metric.catalog.search"));
	}

	private record FixedCapabilityProvider(String capabilityId, CapabilityRouteResult routeResult)
			implements CapabilityProvider {

		@Override
		public boolean enabledForAgent(String agentId) {
			return true;
		}

		@Override
		public CapabilityRouteResult route(String agentId, String query) {
			return routeResult;
		}

		@Override
		public Map<String, ToolCallback> getToolCallbacks(String agentId) {
			return Map.of();
		}

		@Override
		public void refreshMetadata() {
		}

	}

	private static final class NamedToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		private NamedToolCallback(String name) {
			this.toolDefinition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return "{}";
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			return call(toolInput);
		}

	}

}
