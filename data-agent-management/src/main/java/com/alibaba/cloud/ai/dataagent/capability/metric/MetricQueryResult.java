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
import java.util.Map;
import lombok.Builder;
import org.springframework.util.StringUtils;

@Builder
record MetricQueryResult(String status, String summary, String clarificationMessage,
		List<MetricRequiredParameter> missingRequiredParameters, List<Column> columns, List<Map<String, Object>> rows,
		Map<String, Object> metadata, JsonNode rawData) {

	public MetricQueryResult {
		status = StringUtils.hasText(status) ? status : "SUCCESS";
		clarificationMessage = clarificationMessage == null ? "" : clarificationMessage;
		missingRequiredParameters = missingRequiredParameters == null ? List.of() : List.copyOf(missingRequiredParameters);
		columns = columns == null ? List.of() : List.copyOf(columns);
		rows = rows == null ? List.of() : List.copyOf(rows);
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		rawData = rawData == null ? NullNode.getInstance() : rawData;
	}

	@Builder
	record Column(String name, String type, String description, String unit) {
	}

}
