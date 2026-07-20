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

import java.util.List;
import lombok.Builder;

@Builder
public record CapabilityRouteResult(CapabilityRouteType routeType, double score, String matchedCapabilityId,
		List<String> matchedTargets, String reason, String degradedMessage) {

	public CapabilityRouteResult {
		matchedTargets = matchedTargets == null ? List.of() : List.copyOf(matchedTargets);
		reason = reason == null ? "" : reason;
		degradedMessage = degradedMessage == null ? "" : degradedMessage;
	}

	public static CapabilityRouteResult dbOnly(String reason) {
		return CapabilityRouteResult.builder().routeType(CapabilityRouteType.DB_ONLY).reason(reason).score(0D).build();
	}

	public static CapabilityRouteResult noMatch(String capabilityId, String reason) {
		return CapabilityRouteResult.builder()
			.routeType(CapabilityRouteType.ABSTAIN)
			.matchedCapabilityId(capabilityId)
			.matchedTargets(List.of())
			.score(0D)
			.reason(reason)
			.build();
	}

	public static CapabilityRouteResult unknown(String capabilityId, double score, List<String> matchedTargets,
			String reason) {
		return CapabilityRouteResult.builder()
			.routeType(CapabilityRouteType.UNKNOWN)
			.matchedCapabilityId(capabilityId)
			.matchedTargets(matchedTargets)
			.score(score)
			.reason(reason)
			.build();
	}

}
