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

import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import com.alibaba.cloud.ai.dataagent.util.DocumentConverterUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
class MetricOpenApiSyncService {

	private static final int EMBEDDING_BATCH_SIZE = 10;

	private final MetricCapabilityProperties properties;

	private final WebClient.Builder webClientBuilder;

	private final MetricMetadataParserFactory parserFactory;

	private final AgentVectorStoreService agentVectorStoreService;

	private final MetricCapabilityStatus metricCapabilityStatus;

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final AtomicReference<String> lastSuccessfulHash = new AtomicReference<>("");

	private final AtomicBoolean refreshing = new AtomicBoolean(false);

	private volatile boolean catalogReady;

	@Async
	@EventListener(ApplicationReadyEvent.class)
	public void warmUp() {
		refreshCatalog();
	}

	@Scheduled(fixedDelayString = "${spring.ai.alibaba.data-agent.capabilities.metric-system.refresh-interval-seconds:1800}000")
	public void scheduledRefresh() {
		refreshCatalog();
	}

	public void refreshCatalog() {
		if (!properties.isEnabled()) {
			return;
		}
		if (!refreshing.compareAndSet(false, true)) {
			log.debug("Metric OpenAPI refresh already in progress, skipping.");
			return;
		}
		try {
			doRefresh();
		}
		finally {
			refreshing.set(false);
		}
	}

	private void doRefresh() {
		if (!StringUtils.hasText(properties.getSwaggerUrl())) {
			log.warn("Metric capability enabled but swaggerUrl is empty.");
			return;
		}
		try {
			String rawDocument = webClientBuilder.build()
				.get()
				.uri(properties.getSwaggerUrl())
				.retrieve()
				.bodyToMono(String.class)
				.timeout(Duration.ofMillis(properties.getTimeoutMs()))
				.block();
			if (!StringUtils.hasText(rawDocument)) {
				log.warn("Metric OpenAPI document is empty, keep previous catalog snapshot.");
				return;
			}
			log.info("Fetched metric OpenAPI document successfully. swaggerUrl={}, byteSize={}",
					properties.getSwaggerUrl(), rawDocument.length());
			String currentHash = sha256(rawDocument);
			if (currentHash.equals(lastSuccessfulHash.get()) && catalogReady) {
				return;
			}
			MetricMetadataParser parser = parserFactory.getParser(properties.getParserFormat());
			List<MetricDefinition> definitions = parser.parse(rawDocument);
			if (definitions.isEmpty()) {
				log.warn("Metric OpenAPI parsed but produced no valid metric definitions. swaggerUrl={}, format={}",
						properties.getSwaggerUrl(), properties.getParserFormat());
				return;
			}
			syncToVectorStore(definitions);
			metricDefinitionLookup.refresh(definitions);
			lastSuccessfulHash.set(currentHash);
			catalogReady = true;
			metricCapabilityStatus.markRefreshSuccess(definitions.size(), System.currentTimeMillis());
			log.info("Metric catalog synced to vector store. definitionCount={}, agentId={}, samples={}",
					definitions.size(), Constant.METRIC_GLOBAL_AGENT_ID, summarizeDefinitions(definitions));
		}
		catch (Exception ex) {
			Throwable root = ex;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			String summary = (root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName());
			metricCapabilityStatus.markRefreshFailure(summary, System.currentTimeMillis());
			log.warn("Failed to refresh metric OpenAPI catalog from {}, keep previous snapshot. reason={}",
					properties.getSwaggerUrl(), summary);
			log.debug("Metric catalog refresh failure detail.", ex);
		}
	}

	private void syncToVectorStore(List<MetricDefinition> definitions) {
		try {
			agentVectorStoreService.deleteDocumentsByVectorType(Constant.METRIC_GLOBAL_AGENT_ID,
					com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant.METRIC);
		}
		catch (Exception ex) {
			log.warn("Failed to delete old metric documents, continue with import. agentId={}",
					Constant.METRIC_GLOBAL_AGENT_ID, ex);
		}
		List<Document> documents = new ArrayList<>();
		for (MetricDefinition definition : definitions) {
			documents.add(DocumentConverterUtil.convertMetricToDocument(Constant.METRIC_GLOBAL_AGENT_ID, definition));
		}
		for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
			int end = Math.min(i + EMBEDDING_BATCH_SIZE, documents.size());
			agentVectorStoreService.addDocuments(Constant.METRIC_GLOBAL_AGENT_ID,
					documents.subList(i, end));
		}
	}

	public boolean isReady() {
		return catalogReady;
	}

	private String sha256(String value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder builder = new StringBuilder(hash.length * 2);
		for (byte current : hash) {
			builder.append(String.format("%02x", current));
		}
		return builder.toString();
	}

	private List<String> summarizeDefinitions(List<MetricDefinition> definitions) {
		if (definitions == null || definitions.isEmpty()) {
			return List.of();
		}
		return definitions.stream()
			.limit(5)
			.map(definition -> "%s/%s".formatted(definition.metricCode(), definition.metricName()))
			.toList();
	}

}
