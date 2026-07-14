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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRoutingService {

	private static final String SQL_GUARD_TOOL = "sql_guard.check";

	private final CapabilityRegistry capabilityRegistry;

	public CapabilityRouteResult route(String agentId, String query) {
		List<CapabilityProvider> enabledProviders = capabilityRegistry.listEnabledProviders(agentId);
		if (enabledProviders.isEmpty()) {
			log.info("No capability providers enabled for agent. agentId={}", agentId);
			return CapabilityRouteResult.dbOnly("当前 Agent 未启用外部 capability，保留数据库问数链路。");
		}
		CapabilityRouteResult best = null;
		for (CapabilityProvider provider : enabledProviders) {
			CapabilityRouteResult candidate = provider.route(agentId, query);
			log.info("Capability provider evaluated query. agentId={}, provider={}, routeType={}, score={}, matchedTargets={}, reason={}",
					agentId, provider.capabilityId(), candidate == null || candidate.routeType() == null ? "null"
							: candidate.routeType().name(),
					candidate == null ? null : candidate.score(), candidate == null ? List.of() : candidate.matchedTargets(),
					candidate == null ? "" : candidate.reason());
			if (candidate == null || candidate.routeType() == null
					|| candidate.routeType() == CapabilityRouteType.ABSTAIN) {
				continue;
			}
			if (best == null || candidate.score() > best.score()
					|| (best.routeType() == CapabilityRouteType.UNKNOWN
							&& candidate.routeType() != CapabilityRouteType.UNKNOWN)) {
				best = candidate;
			}
		}
		CapabilityRouteResult resolved = best == null ? CapabilityRouteResult.dbOnly("未匹配到 capability，继续走数据库链路。")
				: best;
		log.info("Capability routing final result. agentId={}, routeType={}, capabilityId={}, score={}, matchedTargets={}, reason={}",
				agentId, resolved.routeType(), resolved.matchedCapabilityId(), resolved.score(), resolved.matchedTargets(),
				resolved.reason());
		return resolved;
	}

	public Map<String, ToolCallback> buildRoutedToolCallbacks(String agentId, CapabilityRouteResult routeResult,
			Map<String, ToolCallback> baseToolCallbacks) {
		Map<String, ToolCallback> routed = new LinkedHashMap<>();
		if (baseToolCallbacks != null && !baseToolCallbacks.isEmpty()) {
			baseToolCallbacks.forEach((name, callback) -> {
				if (isToolAllowed(name, routeResult)) {
					routed.putIfAbsent(name, callback);
				}
			});
		}
		if (routeResult == null || !StringUtils.hasText(routeResult.matchedCapabilityId())
				|| routeResult.routeType() == CapabilityRouteType.DB_ONLY
				|| routeResult.routeType() == CapabilityRouteType.UNKNOWN
				|| routeResult.routeType() == CapabilityRouteType.ABSTAIN) {
			log.info(
					"Capability routed tool callbacks without extra capability tools. agentId={}, routeType={}, retainedToolCount={}, metricToolCount={}, databaseToolCount={}",
					agentId, routeResult == null ? "null" : routeResult.routeType(), routed.size(), countMetricTools(routed),
					countDatabaseTools(routed));
			return routed;
		}
		capabilityRegistry.getProvider(routeResult.matchedCapabilityId())
			.ifPresent(provider -> provider.getToolCallbacks(agentId).forEach((name, callback) -> {
				if (isToolAllowed(name, routeResult)) {
					routed.putIfAbsent(name, callback);
				}
			}));
		log.info(
				"Capability routed tool callbacks with capability tools. agentId={}, routeType={}, capabilityId={}, retainedToolCount={}, metricToolCount={}, databaseToolCount={}",
				agentId, routeResult.routeType(), routeResult.matchedCapabilityId(), routed.size(), countMetricTools(routed),
				countDatabaseTools(routed));
		return routed;
	}

	public String buildRuntimeInstructions(CapabilityRouteResult routeResult) {
		if (routeResult == null) {
			return "";
		}
		return switch (routeResult.routeType()) {
			case METRIC_ONLY -> """
					当前问题已被识别为标准指标问题。
					1. 优先使用 `metric.catalog.search`、`metric.catalog.describe`、`metric.query.execute`。
					2. 不允许改用数据库 SQL 自行计算标准指标。
					3. 如果指标系统报错，应直接说明指标系统暂时不可用，不要静默回退到 SQL 重算。
					""".trim();
			case DB_ONLY, ABSTAIN -> """
					当前问题已被识别为数据库问题。
					1. 使用当前 datasource explorer、semantic、sql_guard 等数据库工具链。
					2. 不要尝试指标系统工具。
					""".trim();
			case MIXED -> """
					当前问题同时包含标准指标与数据库补充诉求。
					1. 先拆分子问题。
					2. 标准指标子问题必须走指标工具。
					3. 非指标补充部分再走数据库工具。
					4. 不允许用 SQL 重新计算已有标准指标。
					5. 最后统一汇总回答。
					""".trim();
			case UNKNOWN -> """
					当前问题的能力归属仍不明确。
					1. 优先澄清关键指标名、时间口径或数据范围。
					2. 在归属明确前，不要盲目调用数据库或指标系统工具。
					""".trim();
		};
	}

	private boolean isToolAllowed(String toolName, CapabilityRouteResult routeResult) {
		if (!StringUtils.hasText(toolName) || routeResult == null) {
			return true;
		}
		boolean metricTool = toolName.startsWith("metric.");
		boolean databaseTool = toolName.startsWith("datasource.") || SQL_GUARD_TOOL.equals(toolName);
		return switch (routeResult.routeType()) {
			case METRIC_ONLY -> !databaseTool;
			case DB_ONLY, ABSTAIN -> !metricTool;
			case MIXED -> true;
			case UNKNOWN -> !metricTool && !databaseTool;
		};
	}

	private int countMetricTools(Map<String, ToolCallback> toolCallbacks) {
		return (int) toolCallbacks.keySet().stream().filter(name -> name != null && name.startsWith("metric.")).count();
	}

	private int countDatabaseTools(Map<String, ToolCallback> toolCallbacks) {
		return (int) toolCallbacks.keySet()
			.stream()
			.filter(name -> name != null && (name.startsWith("datasource.") || SQL_GUARD_TOOL.equals(name)))
			.count();
	}

}
