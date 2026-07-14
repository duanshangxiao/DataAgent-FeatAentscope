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
package com.alibaba.cloud.ai.dataagent.properties;

import com.alibaba.cloud.ai.dataagent.constant.Constant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = Constant.PROJECT_PROPERTIES_PREFIX + ".capabilities.metric-system")
public class MetricCapabilityProperties {

	/**
	 * 默认关闭，避免未配置外部系统时影响现有数据库问数链路。
	 */
	private boolean enabled;

	/**
	 * 指标系统 OpenAPI 文档地址。
	 */
	private String swaggerUrl;

	/**
	 * 指标系统接口基础地址。
	 */
	private String baseUrl;

	/**
	 * Swagger 元数据刷新周期，单位秒。
	 */
	private long refreshIntervalSeconds = 1800;

	/**
	 * 识别为标准指标问题的最小路由分数。
	 */
	private double routeThreshold = 0.75d;

	/**
	 * 指标 HTTP 调用超时时间，单位毫秒。
	 */
	private long timeoutMs = 10000L;

}
