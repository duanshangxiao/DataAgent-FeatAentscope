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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
class MetricQueryRequest {

	private String metricKey;

	private String operationId;

	private String metricCode;

	private String query;

	private Map<String, Object> arguments;

	private TimeRange timeRange;

	private List<String> groupBy;

	private List<Map<String, Object>> filters;

	private List<Map<String, Object>> orderBy;

	private Integer limit;

	private String format;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	static class TimeRange {

		private String start;

		private String end;

		private String granularity;

		private String timezone;

	}

}
