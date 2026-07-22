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
import com.alibaba.cloud.ai.dataagent.entity.BusinessKnowledge;
import com.alibaba.cloud.ai.dataagent.mapper.BusinessKnowledgeMapper;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** The single metric retrieval implementation used by runtime tools and the management page. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricRetrievalService {

	private static final int DEFAULT_LIMIT = 5;

	private static final int MAX_LIMIT = 10;

	private static final double VECTOR_THRESHOLD = 0.4D;

	private final AgentVectorStoreService agentVectorStoreService;

	private final MetricDefinitionLookup metricDefinitionLookup;

	private final BusinessKnowledgeMapper businessKnowledgeMapper;

	public MetricSearchResult search(MetricSearchCommand command) {
		if (command == null || !StringUtils.hasText(command.query())) {
			throw new IllegalArgumentException("指标检索问题不能为空。");
		}
		if (metricDefinitionLookup.listEntries().stream().noneMatch(MetricCatalogEntry::online)) {
			log.info("Metric retrieval skipped because no online catalog is active.");
			return new MetricSearchResult("NO_MATCH", "当前没有可检索的上架指标", command.query(), command.query(),
					List.of(), List.of());
		}
		int limit = Math.min(Math.max(command.limit() == null ? DEFAULT_LIMIT : command.limit(), 1), MAX_LIMIT);
		KnowledgeExpansion expansion = expandWithBusinessKnowledge(command.query(), command.agentId());
		Map<String, ScoredCandidate> candidates = new LinkedHashMap<>();
		collectExactMatches(command.query(), expansion, candidates);

		int candidatePool = Math.max(limit * 4, 20);
		List<Document> documents = agentVectorStoreService.getDocumentsForAgent(Constant.METRIC_GLOBAL_AGENT_ID,
				expansion.effectiveQuery(), DocumentMetadataConstant.METRIC, candidatePool, VECTOR_THRESHOLD);
		int retrievalRank = 0;
		for (Document document : documents) {
			retrievalRank++;
			String identifier = metadataText(document, DocumentMetadataConstant.METRIC_KEY);
			if (!StringUtils.hasText(identifier)) {
				identifier = metadataText(document, DocumentMetadataConstant.METRIC_CODE);
			}
			MetricCatalogEntry entry = metricDefinitionLookup.getOnlineEntry(identifier).orElse(null);
			if (entry == null) {
				continue;
			}
			double score = document.getScore() == null ? 1D / (60D + retrievalRank) : document.getScore();
			ScoredCandidate existing = candidates.get(entry.definition().metricKey());
			if (existing == null || (!existing.exact() && score > existing.score())) {
				candidates.put(entry.definition().metricKey(), new ScoredCandidate(entry, score, false,
						matchedFields(command.query(), entry.definition()), "混合检索候选"));
			}
		}

		List<ScoredCandidate> ranked = candidates.values()
			.stream()
			.sorted((left, right) -> {
				int exactCompare = Boolean.compare(right.exact(), left.exact());
				if (exactCompare != 0) {
					return exactCompare;
				}
				int scoreCompare = Double.compare(right.score(), left.score());
				return scoreCompare != 0 ? scoreCompare
						: left.entry().definition().metricKey().compareTo(right.entry().definition().metricKey());
			})
			.limit(limit)
			.toList();
		String decision = decide(ranked);
		List<MetricSearchResult.Candidate> resultCandidates = new ArrayList<>();
		for (int index = 0; index < ranked.size(); index++) {
			ScoredCandidate candidate = ranked.get(index);
			MetricDefinition definition = candidate.entry().definition();
			resultCandidates.add(new MetricSearchResult.Candidate(definition.metricKey(), definition.metricCode(),
					definition.metricName(), candidate.entry().contract().operationId(), definition.description(),
					definition.aliases(), candidate.score(), index + 1, candidate.matchedFields(),
					!expansion.terms().isEmpty(), candidate.reason()));
		}
		String summary = switch (decision) {
			case "NO_MATCH" -> "未找到可信的上架指标";
			case "AMBIGUOUS" -> "存在多个接近的候选指标，需要澄清";
			default -> "已找到可用指标候选";
		};
		log.info("Metric retrieval completed. decision={}, candidateCount={}, knowledgeEnhanced={}", decision,
				resultCandidates.size(), !expansion.terms().isEmpty());
		return new MetricSearchResult(decision, summary, command.query(), expansion.effectiveQuery(), expansion.terms(),
				resultCandidates);
	}

	private void collectExactMatches(String query, KnowledgeExpansion expansion,
			Map<String, ScoredCandidate> candidates) {
		String normalizedQuery = normalize(query + " " + String.join(" ", expansion.terms()));
		for (MetricCatalogEntry entry : metricDefinitionLookup.listEntries()) {
			if (!entry.online()) {
				continue;
			}
			MetricDefinition definition = entry.definition();
			List<String> fields = matchedFields(normalizedQuery, definition);
			if (!fields.isEmpty()) {
				candidates.put(definition.metricKey(), new ScoredCandidate(entry, 1D, true, fields,
						"命中指标编码、名称或别名"));
			}
		}
	}

	private List<String> matchedFields(String query, MetricDefinition definition) {
		String normalized = normalize(query);
		List<String> fields = new ArrayList<>();
		if (contains(normalized, definition.metricCode())) {
			fields.add("metricCode");
		}
		if (contains(normalized, definition.metricName())) {
			fields.add("metricName");
		}
		if (definition.aliases().stream().anyMatch(alias -> contains(normalized, alias))) {
			fields.add("aliases");
		}
		return List.copyOf(fields);
	}

	private boolean contains(String normalizedQuery, String candidate) {
		return StringUtils.hasText(candidate) && normalizedQuery.contains(normalize(candidate));
	}

	private String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}

	private String decide(List<ScoredCandidate> ranked) {
		if (ranked.isEmpty()) {
			return "NO_MATCH";
		}
		if (ranked.size() > 1) {
			ScoredCandidate first = ranked.get(0);
			ScoredCandidate second = ranked.get(1);
			if (first.exact() == second.exact() && second.score() >= first.score() * 0.97D) {
				return "AMBIGUOUS";
			}
		}
		return "MATCH";
	}

	private KnowledgeExpansion expandWithBusinessKnowledge(String query, String agentId) {
		if (!StringUtils.hasText(agentId) || !agentId.matches("\\d+")) {
			return new KnowledgeExpansion(query, List.of());
		}
		Set<String> terms = new LinkedHashSet<>();
		List<String> descriptions = new ArrayList<>();
		for (BusinessKnowledge knowledge : businessKnowledgeMapper.selectByAgentId(Long.valueOf(agentId))) {
			if (knowledge.getIsRecall() == null || knowledge.getIsRecall() != 1) {
				continue;
			}
			List<String> synonyms = splitAliases(knowledge.getSynonyms());
			boolean matched = contains(normalize(query), knowledge.getBusinessTerm())
					|| synonyms.stream().anyMatch(alias -> contains(normalize(query), alias));
			if (!matched) {
				continue;
			}
			terms.add(knowledge.getBusinessTerm());
			terms.addAll(synonyms);
			if (StringUtils.hasText(knowledge.getDescription())) {
				descriptions.add(knowledge.getDescription());
			}
			if (descriptions.size() >= 2) {
				break;
			}
		}
		String suffix = String.join(" ", terms) + " " + String.join(" ", descriptions);
		return new KnowledgeExpansion(StringUtils.hasText(suffix) ? query + " " + suffix.trim() : query,
				List.copyOf(terms));
	}

	private List<String> splitAliases(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		return List.of(value.split("[,，]"))
			.stream()
			.map(String::trim)
			.filter(StringUtils::hasText)
			.toList();
	}

	private String metadataText(Document document, String key) {
		Object value = document.getMetadata().get(key);
		return value == null ? "" : value.toString();
	}

	private record KnowledgeExpansion(String effectiveQuery, List<String> terms) {
	}

	private record ScoredCandidate(MetricCatalogEntry entry, double score, boolean exact, List<String> matchedFields,
			String reason) {
	}

}
