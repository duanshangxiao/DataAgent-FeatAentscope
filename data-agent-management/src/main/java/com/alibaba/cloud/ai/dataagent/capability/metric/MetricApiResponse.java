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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import lombok.Builder;
import org.springframework.util.StringUtils;

/** Response documentation plus the minimal deterministic extraction contract. */
@Builder
public record MetricApiResponse(String description, String contentType, JsonNode schema, List<JsonNode> examples,
		MetricSuccessCriteria successCriteria, String resultPath, String messagePath) {

	public MetricApiResponse {
		contentType = StringUtils.hasText(contentType) ? contentType : "application/json";
		schema = schema == null ? NullNode.getInstance() : schema;
		examples = examples == null ? List.of() : List.copyOf(examples);
	}

	public static MetricApiResponse empty() {
		return new MetricApiResponse(null, "application/json", NullNode.getInstance(), List.of(), null, null, null);
	}

}
