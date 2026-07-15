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
import lombok.Builder;
import org.springframework.util.StringUtils;

@Builder
public record MetricApiParameter(String name, String location, String jsonPath, String type, boolean required,
		String description, List<String> enumValues, String example, Object defaultValue) {

	public MetricApiParameter {
		location = StringUtils.hasText(location) ? location : "body";
		jsonPath = StringUtils.hasText(jsonPath) ? jsonPath : name;
		enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
	}

}
