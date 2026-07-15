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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetricCapabilityComponentsTest {

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
				        "parameters": [
				          { "name": "storeId", "in": "query", "required": true, "schema": { "type": "string" } },
				          { "name": "granularity", "in": "query", "schema": { "type": "string", "enum": ["DAY", "MONTH"] } }
				        ],
				        "responses": {
				          "200": {
				            "content": {
				              "application/json": {
				                "schema": {
				                  "$ref": "#/components/schemas/GmvResponse"
				                }
				              }
				            }
				          }
				        }
				      }
				    },
				    "/metrics/dau/trend": {
				      "get": {
				        "operationId": "dauTrend",
				        "summary": "DAU",
				        "tags": ["user"],
				        "responses": {
				          "200": {
				            "content": {
				              "application/json": {
				                "schema": {
				                  "type": "object",
				                  "properties": {
				                    "metricCode": { "type": "string", "example": "dau_trend" },
				                    "metricName": { "type": "string", "example": "DAU" }
				                  }
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
				      "GmvResponse": {
				        "type": "object",
				        "properties": {
				          "data": {
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
				"""));

		assertEquals(2, definitions.size());
		assertEquals("gmv_trend", definitions.get(0).metricCode());
		assertEquals("GMV", definitions.get(0).metricName());
		assertEquals("dau_trend", definitions.get(1).metricCode());
		assertEquals("DAU", definitions.get(1).metricName());
	}

	@Test
	void parser_infersMetricCodeAndNameFromSchemaExamples() throws Exception {
		MetricOpenApiParser parser = new MetricOpenApiParser(objectMapper, properties);

		List<MetricDefinition> definitions = parser.parse(objectMapper.readTree("""
				{
				  "paths": {
				    "/indicator/query": {
				      "post": {
				        "operationId": "queryIndicator",
				        "summary": "查询指标数据",
				        "tags": ["metric"],
				        "requestBody": {
				          "content": {
				            "application/json": {
				              "schema": {
				                "type": "object",
				                "properties": {
				                  "metricCode": { "type": "string", "example": "payment_amount" },
				                  "metricName": { "type": "string", "example": "支付金额" },
				                  "granularity": { "type": "string", "enum": ["DAY", "MONTH"] }
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
				                    "rows": { "type": "array" }
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
	void execute_returnsClarificationWhenRequiredParameterMissing() {
		MetricCapabilityProperties execProps = new MetricCapabilityProperties();
		execProps.setBaseUrl("http://localhost:18080");
		MetricDefinitionLookup lookup = new MetricDefinitionLookup();
		lookup.refresh(List.of(MetricDefinition.builder()
			.metricCode("store_sales_trend")
			.metricName("门店销售额趋势")
			.summary("查询门店销售额趋势")
			.description("按门店和时间范围查询销售额趋势")
			.operationId("storeSalesTrend")
			.httpMethod("POST")
			.path("/metrics/store/sales/trend")
			.requestParameters(List.of(
					MetricApiParameter.builder().name("storeId").location("query").required(true).description("门店ID").build()))
			.build()));

		MetricCircuitBreaker cb = new MetricCircuitBreaker(new MetricCapabilityStatus());
		MetricQueryExecutionService service = new MetricQueryExecutionService(lookup, execProps, WebClient.builder(),
				cb, objectMapper);
		MetricQueryRequest request = new MetricQueryRequest();
		request.setOperationId("storeSalesTrend");
		request.setQuery("查询销售额趋势");
		request.setArguments(Map.of());

		MetricQueryResult result = service.execute(request);

		assertEquals("NEED_CLARIFICATION", result.status());
		assertTrue(result.clarificationMessage().contains("storeId"));
		assertEquals(1, result.missingRequiredParameters().size());
	}

	@Test
	void route_returnsMixedWhenMetricAvailable() {
		MetricCapabilityProperties props = new MetricCapabilityProperties();
		props.setEnabled(true);

		MetricCapabilityProvider provider = new MetricCapabilityProvider(props, null, null,
				new MetricCircuitBreaker(new MetricCapabilityStatus()), new MetricCapabilityStatus()) {
			@Override
			public boolean enabledForAgent(String agentId) {
				return true;
			}
		};

		var result = provider.route("1", "查询最近7天门店销售额趋势");

		assertEquals(CapabilityRouteType.MIXED, result.routeType());
		assertEquals("metric-system", result.matchedCapabilityId());
	}

}
