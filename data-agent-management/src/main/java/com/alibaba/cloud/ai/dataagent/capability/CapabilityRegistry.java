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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class CapabilityRegistry {

	private final List<CapabilityProvider> providers;

	public List<CapabilityProvider> listEnabledProviders(String agentId) {
		if (providers == null || providers.isEmpty()) {
			return List.of();
		}
		return providers.stream().filter(provider -> provider != null && provider.enabledForAgent(agentId)).toList();
	}

	public Optional<CapabilityProvider> getProvider(String capabilityId) {
		if (!StringUtils.hasText(capabilityId) || providers == null || providers.isEmpty()) {
			return Optional.empty();
		}
		return providers.stream().filter(provider -> capabilityId.equals(provider.capabilityId())).findFirst();
	}

	public Map<String, CapabilityProvider> snapshot() {
		if (providers == null || providers.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, CapabilityProvider> snapshot = new LinkedHashMap<>();
		for (CapabilityProvider provider : providers) {
			if (provider == null || !StringUtils.hasText(provider.capabilityId())) {
				continue;
			}
			snapshot.putIfAbsent(provider.capabilityId(), provider);
		}
		return Collections.unmodifiableMap(snapshot);
	}

}
