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
import com.alibaba.cloud.ai.dataagent.entity.BusinessKnowledge;
import com.alibaba.cloud.ai.dataagent.mapper.BusinessKnowledgeMapper;
import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class MetricCapabilityComponentsTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MetricCapabilityProperties properties = new MetricCapabilityProperties();

	@Test
	void parser_extractsMetricDefinitionsFromOpenApi() throws Exception {
		MetricOpenApiParser parser = new MetricOpenApiParser(objectMapper, properties);

		ParsedMetricCatalog catalog = parser.parse(objectMapper.readTree("""
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

		List<MetricCatalogEntry> entries = catalog.entries();
		assertEquals(2, entries.size());
		List<MetricDefinition> definitions = entries.stream().map(MetricCatalogEntry::definition).toList();
		assertEquals("gmv_trend", definitions.get(0).metricCode());
		assertEquals("GMV", definitions.get(0).metricName());
		assertEquals("gmvTrend", entries.get(0).contract().operationId());
		assertEquals("POST", entries.get(0).contract().httpMethod());
		assertEquals("dau_trend", definitions.get(1).metricCode());
		assertEquals("DAU", definitions.get(1).metricName());
	}

	@Test
	void parser_infersMetricCodeAndNameFromSchemaExamples() throws Exception {
		MetricOpenApiParser parser = new MetricOpenApiParser(objectMapper, properties);

		ParsedMetricCatalog catalog = parser.parse(objectMapper.readTree("""
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

		List<MetricDefinition> definitions = catalog.entries().stream().map(MetricCatalogEntry::definition).toList();
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
		MetricDefinition definition = MetricDefinition.builder()
			.metricKey("store_sales_trend")
			.metricCode("store_sales_trend")
			.metricName("门店销售额趋势")
			.summary("查询门店销售额趋势")
			.description("按门店和时间范围查询销售额趋势")
			.build();
		MetricApiContract contract = MetricApiContract.builder()
			.operationId("storeSalesTrend")
			.httpMethod("POST")
			.path("/metrics/store/sales/trend")
			.requestParameters(List.of(
					MetricApiParameter.builder().name("storeId").location("query").required(true).description("门店ID").build()))
			.build();
		lookup.refresh(List.of(new MetricCatalogEntry(definition, contract,
				new MetricBinding(definition.metricKey(), contract.operationId()), MetricServiceStatus.ONLINE, false,
				"COMPLETED", null)), "test-generation");

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
	void retrieval_usesBusinessKnowledgeAndExcludesOfflineMetrics() {
		MetricDefinitionLookup lookup = new MetricDefinitionLookup();
		MetricCatalogEntry sales = entry("sales_amount", "SALES_AMOUNT", "销售额", List.of("成交额"),
				MetricServiceStatus.ONLINE);
		MetricCatalogEntry refund = entry("refund_amount", "REFUND_AMOUNT", "退款金额", List.of(),
				MetricServiceStatus.OFFLINE);
		lookup.refresh(List.of(sales, refund), "test-generation");

		AgentVectorStoreService vectorStoreService = mock(AgentVectorStoreService.class);
		when(vectorStoreService.getDocumentsForAgent(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
			.thenReturn(List.of());
		BusinessKnowledgeMapper knowledgeMapper = mock(BusinessKnowledgeMapper.class);
		BusinessKnowledge knowledge = BusinessKnowledge.builder()
			.businessTerm("销售额")
			.synonyms("盘子,流水")
			.description("支付成功订单的实付金额")
			.isRecall(1)
			.build();
		when(knowledgeMapper.selectByAgentId(1L)).thenReturn(List.of(knowledge));
		MetricRetrievalService retrievalService = new MetricRetrievalService(vectorStoreService, lookup,
				knowledgeMapper);

		MetricSearchResult enhanced = retrievalService.search(new MetricSearchCommand("看看本月盘子", "1", 5));
		assertEquals("MATCH", enhanced.decision());
		assertEquals("sales_amount", enhanced.candidates().get(0).metricKey());
		assertTrue(enhanced.businessKnowledgeTerms().contains("销售额"));

		Document offlineDocument = Document.builder()
			.text("退款金额")
			.metadata("metricKey", "refund_amount")
			.score(0.9D)
			.build();
		when(vectorStoreService.getDocumentsForAgent(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
			.thenReturn(List.of(offlineDocument));
		MetricSearchResult offline = retrievalService.search(new MetricSearchCommand("退款金额", null, 5));
		assertEquals("NO_MATCH", offline.decision());
		assertTrue(offline.candidates().isEmpty());
	}

	@Test
	void retrieval_skipsVectorSearchWhenNoOnlineCatalogIsActive() {
		MetricDefinitionLookup lookup = new MetricDefinitionLookup();
		AgentVectorStoreService vectorStoreService = mock(AgentVectorStoreService.class);
		BusinessKnowledgeMapper knowledgeMapper = mock(BusinessKnowledgeMapper.class);
		MetricRetrievalService retrievalService = new MetricRetrievalService(vectorStoreService, lookup,
				knowledgeMapper);

		MetricSearchResult result = retrievalService.search(new MetricSearchCommand("本月销售额趋势", null, 5));

		assertEquals("NO_MATCH", result.decision());
		assertTrue(result.candidates().isEmpty());
		verifyNoInteractions(vectorStoreService, knowledgeMapper);
	}

	@Test
	void execute_rejectsOfflineMetricEvenWhenIdentifierIsKnown() {
		MetricDefinitionLookup lookup = new MetricDefinitionLookup();
		lookup.refresh(List.of(entry("sales_amount", "SALES_AMOUNT", "销售额", List.of(),
				MetricServiceStatus.OFFLINE)), "test-generation");
		MetricQueryExecutionService service = new MetricQueryExecutionService(lookup, properties, WebClient.builder(),
				new MetricCircuitBreaker(new MetricCapabilityStatus()), objectMapper);
		MetricQueryRequest request = new MetricQueryRequest();
		request.setMetricCode("SALES_AMOUNT");

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.execute(request));
		assertTrue(error.getMessage().contains("已下架"));
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

	private MetricCatalogEntry entry(String metricKey, String metricCode, String metricName, List<String> aliases,
			MetricServiceStatus status) {
		MetricDefinition definition = MetricDefinition.builder()
			.metricKey(metricKey)
			.metricCode(metricCode)
			.metricName(metricName)
			.aliases(aliases)
			.description(metricName + "定义")
			.build();
		MetricApiContract contract = MetricApiContract.builder()
			.operationId(metricKey + "Query")
			.httpMethod("GET")
			.path("/metrics/" + metricKey)
			.build();
		return new MetricCatalogEntry(definition, contract, new MetricBinding(metricKey, contract.operationId()), status,
				false, "COMPLETED", null);
	}

}
