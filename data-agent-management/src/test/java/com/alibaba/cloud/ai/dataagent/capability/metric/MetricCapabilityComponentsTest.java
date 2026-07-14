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

import com.alibaba.cloud.ai.dataagent.capability.CapabilityRouteType;
import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricCapabilityComponentsTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MetricCapabilityProperties properties = new MetricCapabilityProperties();

	@Test
	void parser_extractsMetricDefinitionsFromOpenApi() throws Exception {
		MetricOpenApiParser parser = new MetricOpenApiParser(objectMapper, properties);

		List<MetricDefinition> definitions = parser.parse(objectMapper.readTree("""
				{
				  "paths": {
				    "/metrics/gmv/trend": {
				      "post": {
				        "operationId": "gmvTrend",
				        "summary": "GMV",
				        "x-metric-code": "gmv_trend",
				        "x-metric-name": "GMV",
				        "x-aliases": ["成交额"],
				        "tags": ["sales"],
				        "requestBody": {
				          "content": {
				            "application/json": {
				              "schema": {
				                "type": "object",
				                "properties": {
				                  "granularity": { "type": "string", "enum": ["DAY", "WEEK"] },
				                  "groupBy": { "type": "array", "items": { "type": "string" } }
				                }
				              }
				            }
				          }
				        },
				        "responses": {
				          "200": {
				            "content": {
				              "application/json": {
				                "schema": {
				                  "type": "object",
				                  "properties": {
				                    "rows": {
				                      "type": "array",
				                      "items": {
				                        "type": "object",
				                        "properties": {
				                          "date": { "type": "string" },
				                          "gmv": { "type": "number" }
				                        }
				                      }
				                    }
				                  }
				                }
				              }
				            }
				          }
				        }
				      }
				    }
				  }
				}
				"""));

		assertEquals(1, definitions.size());
		assertEquals("gmv_trend", definitions.get(0).metricCode());
		assertEquals("GMV", definitions.get(0).metricName());
		assertEquals("GMV", definitions.get(0).summary());
		assertEquals(List.of("DAY", "WEEK"), definitions.get(0).supportedGranularities());
	}

	@Test
	void parser_supportsWildcardContentAndSkipsCrudEndpoints() throws Exception {
		MetricOpenApiParser parser = new MetricOpenApiParser(objectMapper, properties);

		List<MetricDefinition> definitions = parser.parse(objectMapper.readTree("""
				{
				  "paths": {
				    "/test/user/save": {
				      "post": {
				        "summary": "新增用户",
				        "operationId": "saveUser",
				        "responses": {
				          "200": {
				            "content": {
				              "*/*": {
				                "schema": {
				                  "type": "object",
				                  "properties": {
				                    "success": { "type": "boolean" }
				                  }
				                }
				              }
				            }
				          }
				        }
				      }
				    },
				    "/test/metrics/mock/trend": {
				      "get": {
				        "summary": "获取指标趋势",
				        "operationId": "getTrend",
				        "tags": ["测试指标 Mock 接口"],
				        "responses": {
				          "200": {
				            "content": {
				              "*/*": {
				                "schema": {
				                  "$ref": "#/components/schemas/ApiResponseMetricTrendResponse"
				                }
				              }
				            }
				          }
				        }
				      }
				    }
				  },
				  "components": {
				    "schemas": {
				      "ApiResponseMetricTrendResponse": {
				        "type": "object",
				        "properties": {
				          "data": {
				            "$ref": "#/components/schemas/MetricTrendResponse"
				          }
				        }
				      },
				      "MetricTrendResponse": {
				        "type": "object",
				        "description": "指标趋势响应",
				        "properties": {
				          "metricCode": {
				            "type": "string",
				            "example": "active_user_count"
				          },
				          "metricName": {
				            "type": "string",
				            "example": "活跃用户数"
				          }
				        }
				      }
				    }
				  }
				}
				"""));

		assertEquals(1, definitions.size());
		assertEquals("active_user_count", definitions.get(0).metricCode());
		assertEquals("活跃用户数", definitions.get(0).metricName());
	}

	@Test
	void parser_usesFirstAvailableContentSchemaWhenJsonMediaTypeMissing() throws Exception {
		MetricOpenApiParser parser = new MetricOpenApiParser(objectMapper, properties);

		List<MetricDefinition> definitions = parser.parse(objectMapper.readTree("""
				{
				  "paths": {
				    "/metrics/payment/ranking": {
				      "post": {
				        "summary": "获取支付金额排行",
				        "operationId": "getRanking",
				        "tags": ["metric-api"],
				        "requestBody": {
				          "content": {
				            "application/vnd.api+json": {
				              "schema": {
				                "type": "object",
				                "properties": {
				                  "granularity": { "type": "string", "enum": ["DAY", "MONTH"] }
				                }
				              }
				            }
				          }
				        },
				        "responses": {
				          "200": {
				            "content": {
				              "application/vnd.api+json": {
				                "schema": {
				                  "type": "object",
				                  "properties": {
				                    "metricCode": { "type": "string", "example": "payment_amount" },
				                    "metricName": { "type": "string", "example": "支付金额" }
				                  }
				                }
				              }
				            }
				          }
				        }
				      }
				    }
				  }
				}
				"""));

		assertEquals(1, definitions.size());
		assertEquals("payment_amount", definitions.get(0).metricCode());
		assertEquals("支付金额", definitions.get(0).metricName());
		assertEquals(List.of("DAY", "MONTH"), definitions.get(0).supportedGranularities());
	}

	@Test
	void catalogSearch_returnsTopMatchBasedOnApiSummaryAndDescription() {
		MetricCatalogIndex index = new MetricCatalogIndex(properties, null);
		index.refresh(List.of(MetricDefinition.builder()
			.metricCode("foo_api")
			.metricName("无关编码")
			.summary("查询门店销售额趋势")
			.description("按时间范围返回门店销售额趋势数据")
			.operationId("storeSalesTrend")
			.httpMethod("POST")
			.path("/metrics/store/sales/trend")
			.requestParameters(List.of(MetricApiParameter.builder().name("storeId").location("query").required(true).build()))
			.tags(List.of("sales"))
			.build(), MetricDefinition.builder()
				.metricCode("bar_api")
				.metricName("无关编码")
				.summary("查询活跃用户趋势")
				.description("按时间范围返回活跃用户趋势")
				.operationId("userActiveTrend")
				.httpMethod("POST")
				.path("/metrics/dau/trend")
				.tags(List.of("user"))
				.build()));

		List<MetricCatalogSearchCandidate> candidates = index.search("查询上海门店最近7天销售额趋势", 5);

		assertFalse(candidates.isEmpty());
		assertEquals("storeSalesTrend", candidates.get(0).definition().apiId());
	}

	@Test
	void execute_returnsClarificationWhenRequiredParameterMissing() {
		MetricCatalogIndex index = new MetricCatalogIndex(properties, null);
		index.refresh(List.of(MetricDefinition.builder()
			.metricCode("store_sales_trend")
			.metricName("门店销售额趋势")
			.summary("查询门店销售额趋势")
			.description("按门店和时间范围查询销售额趋势")
			.operationId("storeSalesTrend")
			.httpMethod("POST")
			.path("/metrics/store/sales/trend")
			.requestParameters(List.of(
					MetricApiParameter.builder().name("storeId").location("query").required(true).description("门店ID").build(),
					MetricApiParameter.builder().name("startDate").location("body").jsonPath("timeRange.startDate").required(true)
						.description("开始日期").build()))
			.build()));
		MetricCapabilityProperties properties = new MetricCapabilityProperties();
		properties.setBaseUrl("http://localhost:18080");
		MetricQueryExecutionService service = new MetricQueryExecutionService(index, properties, WebClient.builder(),
				new MetricCircuitBreaker(), objectMapper);
		MetricQueryRequest request = new MetricQueryRequest();
		request.setOperationId("storeSalesTrend");
		request.setQuery("查询最近7天销售额趋势");
		request.setArguments(Map.of());

		MetricQueryResult result = service.execute(request);

		assertEquals("NEED_CLARIFICATION", result.status());
		assertTrue(result.clarificationMessage().contains("storeId"));
		assertEquals(1, result.missingRequiredParameters().size());
	}

	@Test
	void route_returnsMetricOnlyEvenWhenQueryContainsDatabaseKeywords() {
		MetricCapabilityProperties properties = new MetricCapabilityProperties();
		properties.setEnabled(true);
		properties.setRouteThreshold(0.6D);
		MetricCatalogIndex index = new MetricCatalogIndex(properties, null);
		index.refresh(List.of(MetricDefinition.builder()
			.metricCode("store_sales_trend")
			.metricName("门店销售额趋势")
			.summary("查询门店销售额趋势")
			.description("按门店和时间范围查询销售额趋势")
			.operationId("storeSalesTrend")
			.httpMethod("POST")
			.path("/metrics/store/sales/trend")
			.requestParameters(List.of(MetricApiParameter.builder().name("storeId").location("query").required(true).build()))
			.build()));
		MetricCapabilityProvider provider = new MetricCapabilityProvider(properties, null, index, null, null,
				new MetricCircuitBreaker()) {
			@Override
			public boolean enabledForAgent(String agentId) {
				return true;
			}
		};

		var result = provider.route("1", "查询最近7天门店销售额趋势，并给我看下明细表结构");

		assertEquals(CapabilityRouteType.METRIC_ONLY, result.routeType());
		assertEquals(List.of("storeSalesTrend"), result.matchedTargets());
	}

}
