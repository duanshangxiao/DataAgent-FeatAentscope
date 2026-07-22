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
import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.entity.MetricLocalConfig;
import com.alibaba.cloud.ai.dataagent.mapper.MetricLocalConfigMapper;
import com.alibaba.cloud.ai.dataagent.properties.MetricCapabilityProperties;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import com.alibaba.cloud.ai.dataagent.util.DocumentConverterUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
public class MetricOpenApiSyncService {

	private static final int EMBEDDING_BATCH_SIZE = 10;

	private final MetricCapabilityProperties properties;

	private final WebClient.Builder webClientBuilder;

	private final MetricMetadataParserFactory parserFactory;

	private final AgentVectorStoreService agentVectorStoreService;

	private final MetricCapabilityStatus metricCapabilityStatus;

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final MetricCatalogAssembler metricCatalogAssembler;

	private final MetricLocalConfigMapper metricLocalConfigMapper;

	private final AtomicReference<String> lastSuccessfulHash = new AtomicReference<>("");

	private final AtomicReference<ParsedMetricCatalog> sourceCatalogRef = new AtomicReference<>(ParsedMetricCatalog.empty());

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
			ParsedMetricCatalog sourceCatalog = parser.parse(rawDocument);
			if (sourceCatalog.entries().isEmpty()) {
				log.warn("Metric OpenAPI parsed but produced no valid metric definitions. swaggerUrl={}, format={}",
						properties.getSwaggerUrl(), properties.getParserFormat());
				return;
			}
			activateCatalog(sourceCatalog);
			sourceCatalogRef.set(sourceCatalog);
			lastSuccessfulHash.set(currentHash);
			catalogReady = true;
			long onlineCount = metricDefinitionLookup.listEntries().stream().filter(MetricCatalogEntry::online).count();
			metricCapabilityStatus.markRefreshSuccess((int) onlineCount, System.currentTimeMillis());
			log.info("Metric catalog synced to vector store. definitionCount={}, agentId={}, samples={}",
					onlineCount, Constant.METRIC_GLOBAL_AGENT_ID,
					summarizeDefinitions(metricDefinitionLookup.listEntries()));
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

	public synchronized void rebuildFromLocalConfig() {
		ParsedMetricCatalog sourceCatalog = sourceCatalogRef.get();
		if (sourceCatalog.entries().isEmpty()) {
			throw new IllegalStateException("指标源目录尚未就绪，无法应用本地配置。");
		}
		activateCatalog(sourceCatalog);
	}

	private synchronized void activateCatalog(ParsedMetricCatalog sourceCatalog) {
		List<MetricCatalogEntry> entries = metricCatalogAssembler.assemble(sourceCatalog);
		String previousGeneration = metricDefinitionLookup.generation();
		String generation = UUID.randomUUID().toString();
		try {
			syncToVectorStore(entries, generation);
			markLocalIndexStatus("COMPLETED", null);
			metricDefinitionLookup.refresh(metricCatalogAssembler.assemble(sourceCatalog), generation);
			deleteGeneration(previousGeneration);
		}
		catch (Exception ex) {
			markLocalIndexStatus("FAILED", abbreviate(ex.getMessage()));
			deleteGeneration(generation);
			throw ex;
		}
	}

	private void syncToVectorStore(List<MetricCatalogEntry> entries, String generation) {
		List<Document> documents = new ArrayList<>();
		for (MetricCatalogEntry entry : entries) {
			if (entry.online()) {
				documents.add(DocumentConverterUtil.convertMetricToDocument(Constant.METRIC_GLOBAL_AGENT_ID,
						entry.definition(), generation));
			}
		}
		if (documents.isEmpty()) {
			return;
		}
		for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
			int end = Math.min(i + EMBEDDING_BATCH_SIZE, documents.size());
			agentVectorStoreService.addDocuments(Constant.METRIC_GLOBAL_AGENT_ID,
					documents.subList(i, end));
		}
	}

	private void deleteGeneration(String generation) {
		if (!StringUtils.hasText(generation)) {
			return;
		}
		try {
			agentVectorStoreService.deleteDocumentsByMetadata(Map.of(Constant.AGENT_ID,
					Constant.METRIC_GLOBAL_AGENT_ID, DocumentMetadataConstant.VECTOR_TYPE,
					DocumentMetadataConstant.METRIC, DocumentMetadataConstant.METRIC_GENERATION, generation));
		}
		catch (Exception ex) {
			log.warn("Failed to clean metric catalog generation. generation={}", generation, ex);
		}
	}

	private void markLocalIndexStatus(String status, String error) {
		for (MetricLocalConfig config : metricLocalConfigMapper.selectAll()) {
			metricLocalConfigMapper.updateIndexStatus(config.getMetricKey(), status, error);
		}
	}

	private String abbreviate(String value) {
		if (!StringUtils.hasText(value)) {
			return "unknown error";
		}
		return value.length() <= 500 ? value : value.substring(0, 500);
	}

	public boolean isReady() {
		return catalogReady;
	}

	public ParsedMetricCatalog sourceCatalog() {
		return sourceCatalogRef.get();
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

	private List<String> summarizeDefinitions(List<MetricCatalogEntry> entries) {
		if (entries == null || entries.isEmpty()) {
			return List.of();
		}
		return entries.stream()
			.limit(5)
			.map(entry -> "%s/%s".formatted(entry.definition().metricCode(), entry.definition().metricName()))
			.toList();
	}

}
