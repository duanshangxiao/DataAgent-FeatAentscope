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

import java.util.List;

public record MetricSearchResult(String decision, String summary, String originalQuery, String effectiveQuery,
		List<String> businessKnowledgeTerms, List<Candidate> candidates) {

	public MetricSearchResult {
		businessKnowledgeTerms = businessKnowledgeTerms == null ? List.of() : List.copyOf(businessKnowledgeTerms);
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
	}

	public record Candidate(String metricKey, String metricCode, String metricName, String operationId,
			String description, List<String> aliases, Double fusedScore, Integer rank, List<String> matchedFields,
			boolean knowledgeEnhanced, String reason) {
	}

}
