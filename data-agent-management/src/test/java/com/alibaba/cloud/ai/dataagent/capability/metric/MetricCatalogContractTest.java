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

import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricCatalogContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MetricCatalogValidator validator = new MetricCatalogValidator();

	@Test
	void standardCatalogParser_preservesTopLevelFilterListAndAppliesDataMetricsProfile() {
		DataMetricsCatalogParser parser = new DataMetricsCatalogParser(objectMapper);

		ParsedMetricCatalog catalog = parser.parse(dataMetricsCatalog("eq"));
		validator.validate(catalog);

		MetricCatalogEntry entry = catalog.entries().get(0);
		assertEquals("data-metrics.metric-service.37", entry.definition().metricKey());
		assertEquals("/admin-api/dev/metricEmploy/data/flight-departure-count", entry.contract().path());
		assertEquals(1, entry.contract().requestParameters().size());
		assertEquals("filterList", entry.contract().requestParameters().get(0).jsonPath());
		assertTrue(entry.contract().requestParameters().get(0).example() instanceof List<?>);
		assertEquals("$.code", entry.contract().response().successCriteria().jsonPath());
		assertEquals("$.data.list", entry.contract().response().resultPath());
		assertEquals("$.msg", entry.contract().response().messagePath());
	}

	@Test
	void standardCatalogParser_rejectsNullOperatorsForDataMetricsV1() {
		DataMetricsCatalogParser parser = new DataMetricsCatalogParser(objectMapper);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> parser.parse(dataMetricsCatalog("isNull")));

		assertTrue(error.getMessage().contains("isNull"));
	}

	@Test
	void commonValidator_rejectsDynamicMetricSelector() throws Exception {
		MetricDefinition definition = MetricDefinition.builder()
			.metricKey("sales.gmv")
			.metricCode("GMV")
			.metricName("成交金额")
			.build();
		MetricApiContract contract = MetricApiContract.builder()
			.operationId("queryGmv")
			.httpMethod("POST")
			.path("/api/metrics/gmv")
			.requestParameters(List.of(MetricApiParameter.builder()
				.name("metricCode")
				.location("body")
				.type("string")
				.build()))
			.requestSchema(objectMapper.readTree("{\"type\":\"object\"}"))
			.response(MetricApiResponse.builder().schema(objectMapper.readTree("{\"type\":\"object\"}")).build())
			.build();
		ParsedMetricCatalog catalog = new ParsedMetricCatalog(List.of(new MetricCatalogEntry(definition, contract,
				new MetricBinding(definition.metricKey(), contract.operationId()), MetricServiceStatus.ONLINE, false,
				"COMPLETED", null)));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> validator.validate(catalog));

		assertTrue(error.getMessage().contains("dynamic metric selector"));
	}

	@Test
	void execution_postsFilterListExtractsRowsAndPreservesRawData() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<ClientRequest> clientRequest = new AtomicReference<>();
		MetricQueryExecutionService service = executionService(
				"""
						{"code":0,"msg":"success","data":{"total":1,"list":[{"FLT_DATE":"2026-07-01","COUNT":12}]}}
						""",
				calls, clientRequest, successEntry());
		MetricQueryRequest request = new MetricQueryRequest();
		request.setMetricKey("data-metrics.metric-service.37");
		request.setArguments(Map.of("filterList",
				List.of(Map.of("field", "CARRIER", "filterCondition", "eq", "filterValue", "MU"))));

		MetricQueryResult result = service.execute(request);

		assertEquals("SUCCESS", result.status());
		assertEquals(1, calls.get());
		assertEquals(HttpMethod.POST, clientRequest.get().method());
		assertEquals("/admin-api/dev/metricEmploy/data/flight-departure-count", clientRequest.get().url().getPath());
		assertEquals(1, result.rows().size());
		assertEquals(12, result.rows().get(0).get("COUNT"));
		assertEquals(0, result.rawData().path("code").asInt());
		assertEquals("data-metrics.metric-service.37", result.metadata().get("metricKey"));
		assertFalse((Boolean) result.metadata().get("resultTruncated"));
	}

	@Test
	void execution_distinguishesBusinessErrorAndBlocksNullOperatorBeforeHttp() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		MetricQueryExecutionService service = executionService(
				"""
						{"code":1001003002,"msg":"指标服务不存在或未发布","data":null}
						""",
				calls, new AtomicReference<>(), successEntry());
		MetricQueryRequest businessRequest = new MetricQueryRequest();
		businessRequest.setMetricKey("data-metrics.metric-service.37");
		businessRequest.setArguments(Map.of("filterList", List.of()));

		MetricQueryResult businessResult = service.execute(businessRequest);

		assertEquals("BUSINESS_ERROR", businessResult.status());
		assertEquals("指标服务不存在或未发布", businessResult.summary());
		assertEquals(1001003002, businessResult.rawData().path("code").asInt());
		assertEquals(1, calls.get());

		MetricQueryRequest invalidRequest = new MetricQueryRequest();
		invalidRequest.setMetricKey("data-metrics.metric-service.37");
		invalidRequest.setArguments(Map.of("filterList",
				List.of(Map.of("field", "CARRIER", "filterCondition", "isNotNull", "filterValue", ""))));

		MetricQueryResult invalidResult = service.execute(invalidRequest);

		assertEquals("INVALID_ARGUMENT", invalidResult.status());
		assertEquals(1, calls.get());
	}

	private MetricQueryExecutionService executionService(String responseBody, AtomicInteger calls,
			AtomicReference<ClientRequest> clientRequest, MetricCatalogEntry entry) {
		MetricCapabilityProperties properties = new MetricCapabilityProperties();
		properties.setBaseUrl("http://metric-provider.test");
		properties.setMaxResultRows(200);
		MetricDefinitionLookup lookup = new MetricDefinitionLookup();
		lookup.refresh(List.of(entry), "test-generation");
		ExchangeFunction exchangeFunction = request -> {
			calls.incrementAndGet();
			clientRequest.set(request);
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", "application/json")
				.body(responseBody)
				.build());
		};
		return new MetricQueryExecutionService(lookup, properties,
				WebClient.builder().exchangeFunction(exchangeFunction),
				new MetricCircuitBreaker(new MetricCapabilityStatus()), objectMapper);
	}

	private MetricCatalogEntry successEntry() throws Exception {
		MetricDefinition definition = MetricDefinition.builder()
			.metricKey("data-metrics.metric-service.37")
			.metricCode("flight-departure-count")
			.metricName("每日出港航班总量")
			.build();
		MetricApiContract contract = MetricApiContract.builder()
			.operationId("queryMetricService37")
			.httpMethod("POST")
			.path("/admin-api/dev/metricEmploy/data/flight-departure-count")
			.requestParameters(List.of(MetricApiParameter.builder()
				.name("filterList")
				.location("body")
				.jsonPath("filterList")
				.type("array")
				.required(true)
				.defaultValue(List.of())
				.build()))
			.requestSchema(objectMapper.readTree("{\"type\":\"object\"}"))
			.response(MetricApiResponse.builder()
				.schema(objectMapper.readTree("{\"type\":\"object\"}"))
				.successCriteria(MetricSuccessCriteria.builder()
					.jsonPath("$.code")
					.operator("EQ")
					.expectedValue(objectMapper.valueToTree(0))
					.build())
				.resultPath("$.data.list")
				.messagePath("$.msg")
				.build())
			.build();
		return new MetricCatalogEntry(definition, contract,
				new MetricBinding(definition.metricKey(), contract.operationId()), MetricServiceStatus.ONLINE, false,
				"COMPLETED", null);
	}

	private String dataMetricsCatalog(String operator) {
		return """
				{
				  "specVersion": "data-agent-metric-catalog/1.0",
				  "sourceSystem": "data-metrics",
				  "revision": "test-1",
				  "metrics": [{
				    "definition": {
				      "metricKey": "data-metrics.metric-service.37",
				      "metricCode": "flight-departure-count",
				      "metricName": "每日出港航班总量",
				      "description": "统计每日出港航班总量",
				      "aliases": [],
				      "examples": [],
				      "tags": [],
				      "supportedGranularities": [],
				      "supportedDimensions": [],
				      "supportedFilters": ["CARRIER"]
				    },
				    "invocation": {
				      "operationId": "queryMetricService37",
				      "method": "POST",
				      "path": "/admin-api/dev/metricEmploy/data/flight-departure-count",
				      "parameters": [{
				        "name": "filterList",
				        "location": "body",
				        "jsonPath": "filterList",
				        "type": "array",
				        "required": true,
				        "description": "过滤条件",
				        "enumValues": [],
				        "example": [{
				          "field": "CARRIER",
				          "filterCondition": "%s",
				          "filterValue": "MU"
				        }],
				        "defaultValue": []
				      }],
				      "requestSchema": {
				        "type": "object",
				        "properties": {
				          "filterList": {
				            "type": "array",
				            "items": {
				              "type": "object",
				              "properties": {
				                "filterCondition": {
				                  "type": "string",
				                  "enum": ["%s"]
				                }
				              }
				            }
				          }
				        }
				      },
				      "response": {
				        "description": "查询结果",
				        "contentType": "application/json",
				        "schema": {"type": "object"},
				        "examples": []
				      }
				    }
				  }]
				}
				""".formatted(operator, operator);
	}

}
