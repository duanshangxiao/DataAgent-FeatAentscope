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
			log.info("Capability provider evaluated query. agentId={}, provider={}, routeType={}, reason={}",
					agentId, provider.capabilityId(), candidate == null || candidate.routeType() == null ? "null"
							: candidate.routeType().name(),
					candidate == null ? "" : candidate.reason());
			if (candidate == null || candidate.routeType() == null
					|| candidate.routeType() == CapabilityRouteType.ABSTAIN) {
				continue;
			}
			if (best == null || candidate.score() > best.score()) {
				best = candidate;
			}
		}
		CapabilityRouteResult resolved = best == null ? CapabilityRouteResult.dbOnly("未匹配到 capability，继续走数据库链路。")
				: best;
		log.info("Capability routing final result. agentId={}, routeType={}, capabilityId={}, reason={}",
				agentId, resolved.routeType(), resolved.matchedCapabilityId(), resolved.reason());
		return resolved;
	}

	public Map<String, ToolCallback> buildRoutedToolCallbacks(String agentId, CapabilityRouteResult routeResult,
			Map<String, ToolCallback> baseToolCallbacks) {
		Map<String, ToolCallback> routed = new LinkedHashMap<>();
		if (baseToolCallbacks != null && !baseToolCallbacks.isEmpty()) {
			routed.putAll(baseToolCallbacks);
		}
		if (routeResult == null || !StringUtils.hasText(routeResult.matchedCapabilityId())
				|| routeResult.routeType() == CapabilityRouteType.ABSTAIN) {
			return routed;
		}
		capabilityRegistry.getProvider(routeResult.matchedCapabilityId())
			.ifPresent(provider -> provider.getToolCallbacks(agentId).forEach(routed::putIfAbsent));
		log.info("Capability routed tool callbacks. agentId={}, capabilityId={}, totalToolCount={}",
				agentId, routeResult.matchedCapabilityId(), routed.size());
		return routed;
	}

	public String buildRuntimeInstructions(CapabilityRouteResult routeResult) {
		if (routeResult == null || routeResult.routeType() == null) {
			return "";
		}
		boolean metricAvailable = routeResult.routeType() == CapabilityRouteType.MIXED;
		if (metricAvailable) {
			return """
					当前环境中指标系统可用。
					1. 如果用户问题涉及标准指标查询，优先使用 metric.catalog.search 检索候选指标。
					2. 使用 metric.catalog.describe 获取接口完整契约后，再调用 metric.query.execute 执行查询。
					3. 如果 metric.catalog.search 未找到匹配的指标，可回退使用数据库工具链。
					4. 如果 metric.query.execute 返回 FALLBACK_TO_DB 状态，降级使用数据库工具做近似计算，并在回答中注明"非标准口径，可能存在偏差"。
					5. 如果指标系统报错（非熔断），直接说明指标系统暂时不可用。
					""".trim();
		}
		return """
				当前环境中指标系统不可用。
				1. 使用 datasource explorer、semantic、sql_guard 等数据库工具链。
				2. 不要尝试指标系统工具。
				""".trim();
	}

}
