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

/**
 * 指标元数据解析器接口，支持不同格式的指标目录（OpenAPI、自定义 JSON 等）切换。
 */
public interface MetricMetadataParser {

	/**
	 * 从原始文本中解析指标定义列表。
	 * @param rawDocument 原始元数据文本
	 * @return 解析出的指标定义列表
	 */
	List<MetricDefinition> parse(String rawDocument);

	/**
	 * 解析器名称，用于工厂查找（如 "openapi3"、"custom-catalog"）。
	 */
	String formatName();

}
