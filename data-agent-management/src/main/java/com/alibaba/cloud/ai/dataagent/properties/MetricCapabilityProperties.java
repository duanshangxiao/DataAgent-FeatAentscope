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

import java.util.List;
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
	 * 指标元数据解析器格式（如 openapi3、custom-catalog）。
	 */
	private String parserFormat = "openapi3";

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

	/**
	 * 指标接口单次响应体最大字节数。
	 */
	private int maxResponseBytes = 262_144;

	/**
	 * 结构化展示最多保留的结果行数；原始 JSON 仍完整保留。
	 */
	private int maxResultRows = 200;

	/**
	 * 是否启用 embedding 语义检索，默认开启（需要 EmbeddingModel 可用）。
	 */
	private boolean embeddingEnabled = true;

	/**
	 * Embedding 粗排召回数量，再做关键词精排。
	 */
	private int embeddingTopK = 10;

	/**
	 * Embedding 粗排最低相似度阈值，低于该值的候选不进入精排。
	 */
	private double embeddingMinSimilarity = 0.3D;

	// --- 路由评分权重 ---

	private double summaryWeight = 0.45d;

	private double descriptionWeight = 0.30d;

	private double operationIdWeight = 0.10d;

	private double tagWeight = 0.15d;

	private double requiredParamWeight = 0.12d;

	private double optionalParamWeight = 0.06d;

	private double keywordHitWeight = 0.40d;

	// --- 熔断器配置 ---

	private int circuitBreakerFailureThreshold = 3;

	private long circuitBreakerOpenDurationMs = 30_000L;

	private int circuitBreakerHalfOpenMaxCalls = 2;

	// --- 路由澄清阈值因子 ---

	/**
	 * 路由阈值乘以该因子得到澄清阈值下限。
	 */
	private double clarifyThresholdFactor = 0.6D;

	/**
	 * 澄清阈值最小下限（避免阈值过低导致误匹配）。
	 */
	private double minClarifyThreshold = 0.35D;

	// --- 指标接口识别关键词 ---

	private List<String> metricKeywords = List.of("metric", "metrics", "指标", "gmv", "dau", "mau", "留存", "活跃");

}
