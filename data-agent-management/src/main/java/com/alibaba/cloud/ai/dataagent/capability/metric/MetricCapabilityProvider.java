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

import com.alibaba.cloud.ai.dataagent.capability.CapabilityProvider;
import com.alibaba.cloud.ai.dataagent.capability.CapabilityRouteResult;
import com.alibaba.cloud.ai.dataagent.capability.CapabilityRouteType;
import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.alibaba.cloud.ai.dataagent.service.skill.AgentSkillBindingService;
import com.alibaba.cloud.ai.dataagent.service.skill.LocalSkillService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 指标能力提供者，仅负责运行时判断（可用性、路由、工具）。
 * <p>
 * 不依赖同步/存储等基础设施组件，避免循环依赖。
 */
@Slf4j
@Component
public class MetricCapabilityProvider implements CapabilityProvider {

	public static final String CAPABILITY_ID = "metric-system";

	private final MetricCapabilityProperties properties;

	private final AgentSkillBindingService agentSkillBindingService;

	private final MetricToolProvider metricToolProvider;

	private final MetricCircuitBreaker circuitBreaker;

	private final MetricCapabilityStatus metricCapabilityStatus;

	private final Cache<Long, Boolean> skillBindingCache = Caffeine.newBuilder()
		.maximumSize(100)
		.expireAfterWrite(60, TimeUnit.SECONDS)
		.build();

	public MetricCapabilityProvider(MetricCapabilityProperties properties,
			AgentSkillBindingService agentSkillBindingService, MetricToolProvider metricToolProvider,
			MetricCircuitBreaker circuitBreaker, MetricCapabilityStatus metricCapabilityStatus) {
		this.properties = properties;
		this.agentSkillBindingService = agentSkillBindingService;
		this.metricToolProvider = metricToolProvider;
		this.circuitBreaker = circuitBreaker;
		this.metricCapabilityStatus = metricCapabilityStatus;
	}

	@Override
	public String capabilityId() {
		return CAPABILITY_ID;
	}

	@Override
	public boolean enabledForAgent(String agentId) {
		if (!properties.isEnabled()) {
			return false;
		}
		if (!StringUtils.hasText(agentId)) {
			return false;
		}
		if (!metricCapabilityStatus.isReady()) {
			return false;
		}
		try {
			Long numericAgentId = Long.valueOf(agentId);
			Boolean enabled = skillBindingCache.get(numericAgentId, this::loadSkillBinding);
			return Boolean.TRUE.equals(enabled);
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private Boolean loadSkillBinding(Long numericAgentId) {
		List<String> skillIds = agentSkillBindingService.listSkillIdsByAgentId(numericAgentId);
		boolean enabled = skillIds.contains(LocalSkillService.BUILTIN_METRIC_SYSTEM_SKILL_ID);
		log.debug("Metric capability skill binding loaded. agentId={}, enabled={}", numericAgentId, enabled);
		return enabled;
	}

	@Override
	public CapabilityRouteResult route(String agentId, String query) {
		if (!enabledForAgent(agentId)) {
			return CapabilityRouteResult.noMatch(capabilityId(), "指标能力未启用，不参与当前问题路由。");
		}
		if (circuitBreaker.isOpen()) {
			log.warn("Metric circuit breaker is OPEN, metrics unavailable. agentId={}", agentId);
			return CapabilityRouteResult.noMatch(capabilityId(), "指标系统熔断，暂时不可用。");
		}
		return CapabilityRouteResult.builder()
			.routeType(CapabilityRouteType.MIXED)
			.score(1.0D)
			.matchedCapabilityId(capabilityId())
			.reason("指标系统可用，LLM 自行判断是否调用指标工具。")
			.build();
	}

	@Override
	public Map<String, ToolCallback> getToolCallbacks(String agentId) {
		return enabledForAgent(agentId) ? metricToolProvider.getToolCallbacks() : Map.of();
	}

	@Override
	public void refreshMetadata() {
		skillBindingCache.invalidateAll();
		circuitBreaker.configure(properties.getCircuitBreakerFailureThreshold(),
				properties.getCircuitBreakerOpenDurationMs(), properties.getCircuitBreakerHalfOpenMaxCalls());
	}

}
