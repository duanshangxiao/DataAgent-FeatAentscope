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
import java.util.List;
import lombok.Builder;
import org.springframework.util.StringUtils;

/** Technical contract used only after a business metric has been selected. */
@Builder
public record MetricApiContract(String operationId, String httpMethod, String path,
		List<MetricApiParameter> requestParameters, JsonNode requestSchema, JsonNode responseSchema) {

	public MetricApiContract {
		requestParameters = requestParameters == null ? List.of() : List.copyOf(requestParameters);
	}

	public String apiId() {
		return StringUtils.hasText(operationId) ? operationId : httpMethod + " " + path;
	}

	public List<MetricApiParameter> requiredParameters() {
		return requestParameters.stream().filter(MetricApiParameter::required).toList();
	}

}
