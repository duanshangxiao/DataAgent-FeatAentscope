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
package com.alibaba.cloud.ai.dataagent.observability;

import com.alibaba.cloud.ai.dataagent.agentscope.dto.AgentRequest;
import com.alibaba.cloud.ai.dataagent.agentscope.runtime.QueryClarifyService.QueryClarifyAssessment;
import com.alibaba.cloud.ai.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import com.alibaba.cloud.ai.dataagent.capability.CapabilityRouteResult;
import com.alibaba.cloud.ai.dataagent.agentscope.tool.semantic.SemanticModelSearchHit;
import com.alibaba.cloud.ai.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchResult;
import com.alibaba.cloud.ai.dataagent.service.knowledge.DomainKnowledgeSearchService.KnowledgeHit;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AnswerTraceExplainStore {

	private static final int MAX_SESSION_COUNT = 128;

	private static final int MAX_REQUEST_COUNT_PER_SESSION = 24;

	private final Object monitor = new Object();

	private final ThreadLocal<ExplainContext> currentContext = new ThreadLocal<>();

	private final LinkedHashMap<String, LinkedHashMap<String, ExplainAssembly>> explainsBySession = new LinkedHashMap<>(
			32, 0.75f, true);

	public void openScope(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(request.getThreadId(), request.getRuntimeRequestId());
			applyRequestContext(assembly, request);
			assembly.updatedAt = Instant.now().toEpochMilli();
			currentContext.set(new ExplainContext(request.getThreadId(), request.getRuntimeRequestId()));
			evictOverflowLocked();
		}
	}

	public void closeScope() {
		currentContext.remove();
	}

	public void recordFinalAnswer(String answer) {
		withCurrentAssembly(assembly -> {
			assembly.answer = answer;
			assembly.updatedAt = Instant.now().toEpochMilli();
		});
	}

	public void recordWarning(String warning) {
		if (!StringUtils.hasText(warning)) {
			return;
		}
		withCurrentAssembly(assembly -> {
			assembly.warnings.add(warning.trim());
			assembly.updatedAt = Instant.now().toEpochMilli();
		});
	}

	public void recordClarifyAssessment(AgentRequest request, QueryClarifyAssessment assessment) {
		if (assessment == null) {
			return;
		}
		withAssembly(request, assembly -> applyClarifyAssessment(assembly, assessment));
	}

	public void recordSemanticSearch(String query, String summary, List<SemanticModelSearchHit> hits) {
		withCurrentAssembly(assembly -> applySemanticSearch(assembly, query, summary, hits));
	}

	public void recordSemanticSearch(AgentRequest request, String query, String summary,
			List<SemanticModelSearchHit> hits) {
		withAssembly(request, assembly -> applySemanticSearch(assembly, query, summary, hits));
	}

	public void recordKnowledgeSearch(DomainKnowledgeSearchResult result) {
		if (result == null) {
			return;
		}
		withCurrentAssembly(assembly -> applyKnowledgeSearch(assembly, result));
	}

	public void recordKnowledgeSearch(AgentRequest request, DomainKnowledgeSearchResult result) {
		if (result == null) {
			return;
		}
		withAssembly(request, assembly -> applyKnowledgeSearch(assembly, result));
	}

	public void recordDatasourceResult(DatasourceExplorerResult result) {
		if (result == null) {
			return;
		}
		withCurrentAssembly(assembly -> applyDatasourceResult(assembly, result));
	}

	public void recordDatasourceResult(AgentRequest request, DatasourceExplorerResult result) {
		if (result == null) {
			return;
		}
		withAssembly(request, assembly -> applyDatasourceResult(assembly, result));
	}

	public void recordCapabilityRouting(AgentRequest request, CapabilityRouteResult routeResult) {
		if (routeResult == null) {
			return;
		}
		withAssembly(request, assembly -> applyCapabilityRouting(assembly, routeResult));
	}

	public void recordMetricCatalogSearch(AgentRequest request, String query, String summary, List<String> matchedMetrics) {
		withAssembly(request, assembly -> applyMetricCatalogSearch(assembly, query, summary, matchedMetrics));
	}

	public void recordMetricQueryResult(AgentRequest request, String metricCode, String summary) {
		withAssembly(request, assembly -> applyMetricQueryResult(assembly, metricCode, summary));
	}

	public Optional<AnswerTraceExplainView> getExplain(String sessionId, String runtimeRequestId) {
		if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(runtimeRequestId)) {
			return Optional.empty();
		}
		synchronized (monitor) {
			LinkedHashMap<String, ExplainAssembly> explainsByRequest = explainsBySession.get(sessionId);
			if (explainsByRequest == null) {
				return Optional.empty();
			}
			ExplainAssembly assembly = explainsByRequest.get(runtimeRequestId);
			if (assembly == null) {
				return Optional.empty();
			}
			return Optional.of(assembly.toView());
		}
	}

	public Optional<AnswerTraceExplainView> getLatestExplain(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return Optional.empty();
		}
		synchronized (monitor) {
			LinkedHashMap<String, ExplainAssembly> explainsByRequest = explainsBySession.get(sessionId);
			if (explainsByRequest == null || explainsByRequest.isEmpty()) {
				return Optional.empty();
			}
			ExplainAssembly latestAssembly = explainsByRequest.values()
				.stream()
				.max(java.util.Comparator.comparingLong(assembly -> assembly.updatedAt))
				.orElse(null);
			return latestAssembly == null ? Optional.empty() : Optional.of(latestAssembly.toView());
		}
	}

	public Optional<ExplainMirrorSummary> getMirrorSummary(String sessionId, String runtimeRequestId) {
		return getExplain(sessionId, runtimeRequestId).map(explain -> ExplainMirrorSummary.builder()
			.datasource(explain.getDatasource())
			.semanticHitCount(explain.getSemanticHits() == null ? 0 : explain.getSemanticHits().size())
			.knowledgeHitCount(explain.getKnowledgeHits() == null ? 0 : explain.getKnowledgeHits().size())
			.toolStepCount(explain.getToolSteps() == null ? 0 : explain.getToolSteps().size())
			.build());
	}

	private void withCurrentAssembly(java.util.function.Consumer<ExplainAssembly> consumer) {
		ExplainContext context = currentContext.get();
		if (context == null) {
			return;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(context.sessionId, context.runtimeRequestId);
			consumer.accept(assembly);
		}
	}

	private void withAssembly(AgentRequest request, java.util.function.Consumer<ExplainAssembly> consumer) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(request.getThreadId(), request.getRuntimeRequestId());
			applyRequestContext(assembly, request);
			consumer.accept(assembly);
			evictOverflowLocked();
		}
	}

	private ExplainAssembly resolveAssemblyLocked(String sessionId, String runtimeRequestId) {
		LinkedHashMap<String, ExplainAssembly> explainsByRequest = explainsBySession.computeIfAbsent(sessionId,
				ignored -> new LinkedHashMap<>(8, 0.75f, true));
		return explainsByRequest.computeIfAbsent(runtimeRequestId, ignored -> new ExplainAssembly());
	}

	private void applyRequestContext(ExplainAssembly assembly, AgentRequest request) {
		if (assembly == null || request == null) {
			return;
		}
		if (StringUtils.hasText(request.getThreadId())) {
			assembly.sessionId = request.getThreadId();
		}
		if (StringUtils.hasText(request.getRuntimeRequestId())) {
			assembly.runtimeRequestId = request.getRuntimeRequestId();
		}
		if (StringUtils.hasText(request.getAgentId())) {
			assembly.agentId = request.getAgentId();
		}
		if (StringUtils.hasText(request.getQuery())) {
			assembly.question = request.getQuery();
		}
	}

	private void applySemanticSearch(ExplainAssembly assembly, String query, String summary,
			List<SemanticModelSearchHit> hits) {
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("semantic_model.search")
			.title("语义匹配")
			.summary(summary)
			.detail(query)
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		if (hits != null) {
			for (SemanticModelSearchHit hit : hits) {
				if (hit == null) {
					continue;
				}
				assembly.semanticHits.add(SemanticHitView.builder()
					.tableName(hit.getTableName())
					.columnName(hit.getColumnName())
					.businessName(hit.getBusinessName())
					.businessDescription(hit.getBusinessDescription())
					.matchedBy(hit.getMatchedBy())
					.score(hit.getScore())
					.relationHint(hit.getRelationHint())
					.build());
			}
		}
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyKnowledgeSearch(ExplainAssembly assembly, DomainKnowledgeSearchResult result) {
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("domain_business_knowledge.search")
			.title("业务知识检索")
			.summary("检索到 %d 条知识命中".formatted(result.hits() == null ? 0 : result.hits().size()))
			.detail(result.resolution())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		if (result.hits() != null) {
			for (KnowledgeHit hit : result.hits()) {
				if (hit == null) {
					continue;
				}
				assembly.knowledgeHits.add(KnowledgeHitView.builder()
					.vectorType(hit.vectorType())
					.knowledgeId(hit.knowledgeId())
					.title(hit.title())
					.summary(hit.summary())
					.snippet(hit.snippet())
					.source(hit.source())
					.concreteType(hit.concreteType())
					.build());
			}
		}
		if (result.warnings() != null) {
			assembly.warnings
				.addAll(result.warnings().stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyDatasourceResult(ExplainAssembly assembly, DatasourceExplorerResult result) {
		if (StringUtils.hasText(result.getDatasource())) {
			assembly.datasource = result.getDatasource();
		}
		if (StringUtils.hasText(result.getSql())) {
			assembly.sql = result.getSql();
		}
		if (result.getUsedTables() != null && !result.getUsedTables().isEmpty()) {
			assembly.usedTables.clear();
			assembly.usedTables
				.addAll(result.getUsedTables().stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		if (result.getUsedColumns() != null && !result.getUsedColumns().isEmpty()) {
			assembly.usedColumns.clear();
			assembly.usedColumns
				.addAll(result.getUsedColumns().stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		if (result.getRelationEvidence() != null && !result.getRelationEvidence().isEmpty()) {
			assembly.relationEvidence.clear();
			assembly.relationEvidence.addAll(result.getRelationEvidence());
		}
		if (StringUtils.hasText(result.getResultScope())) {
			assembly.resultScope = result.getResultScope();
		}
		if (StringUtils.hasText(result.getDecisionReason())) {
			assembly.decisionReason = result.getDecisionReason();
		}
		if (result.getToolDecisionReasons() != null && !result.getToolDecisionReasons().isEmpty()) {
			assembly.toolDecisionReasons.clear();
			assembly.toolDecisionReasons.addAll(
					result.getToolDecisionReasons().stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		if (result.getResultScopeDetails() != null && !result.getResultScopeDetails().isEmpty()) {
			assembly.resultScopeDetails.clear();
			assembly.resultScopeDetails.addAll(
					result.getResultScopeDetails().stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("datasource.explorer")
			.title(result.getAction())
			.summary(result.getSummary())
			.detail(result.getSql())
			.datasource(result.getDatasource())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyCapabilityRouting(ExplainAssembly assembly, CapabilityRouteResult routeResult) {
		assembly.routeType = routeResult.routeType() == null ? null : routeResult.routeType().name();
		assembly.capabilityId = routeResult.matchedCapabilityId();
		assembly.matchedMetrics.clear();
		assembly.matchedMetrics.addAll(routeResult.matchedTargets());
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("capability.route")
			.title("能力路由")
			.summary(routeResult.reason())
			.detail("routeType=%s, matchedTargets=%s".formatted(routeResult.routeType(), routeResult.matchedTargets()))
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyMetricCatalogSearch(ExplainAssembly assembly, String query, String summary,
			List<String> matchedMetrics) {
		assembly.matchedMetrics.clear();
		if (matchedMetrics != null) {
			assembly.matchedMetrics.addAll(matchedMetrics.stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("metric.catalog.search")
			.title("指标目录检索")
			.summary(summary)
			.detail(query)
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyMetricQueryResult(ExplainAssembly assembly, String metricCode, String summary) {
		if (StringUtils.hasText(metricCode)) {
			assembly.matchedMetrics.clear();
			assembly.matchedMetrics.add(metricCode.trim());
		}
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("metric.query.execute")
			.title("指标查询执行")
			.summary(summary)
			.detail(metricCode)
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyClarifyAssessment(ExplainAssembly assembly, QueryClarifyAssessment assessment) {
		assembly.clarify.put("riskLevel", assessment.riskLevel().value());
		assembly.clarify.put("clarifyRequired", assessment.clarifyRequired());
		assembly.clarify.put("missingDimensions", assessment.missingDimensions());
		assembly.clarify.put("followUpQuestions", assessment.followUpQuestions());
		assembly.clarify.put("suggestedAssumptions", assessment.suggestedAssumptions());
		assembly.clarify.put("summary", assessment.summary());
		assembly.clarify.put("userMessage", assessment.userMessage());
		assembly.clarify.put("shouldBlockExecution", assessment.shouldBlockExecution());
		if (StringUtils.hasText(assessment.feedbackContent())) {
			assembly.clarify.put("humanFeedbackContent", assessment.feedbackContent());
		}
		assembly.toolSteps.add(ToolStepView.builder()
			.toolName("query_clarify.check")
			.title("问题歧义检查")
			.summary(assessment.summary())
			.detail(assessment.userMessage())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		if (assessment.shouldBlockExecution()) {
			assembly.warnings.add("riskLevel=high，已禁止直接查库，需先补充信息或明确假设。");
		}
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void evictOverflowLocked() {
		while (explainsBySession.size() > MAX_SESSION_COUNT) {
			String eldestSessionId = explainsBySession.keySet().iterator().next();
			explainsBySession.remove(eldestSessionId);
		}
		explainsBySession.values().forEach(this::evictRequestOverflowLocked);
	}

	private void evictRequestOverflowLocked(LinkedHashMap<String, ExplainAssembly> explainsByRequest) {
		while (explainsByRequest.size() > MAX_REQUEST_COUNT_PER_SESSION) {
			String eldestRequestId = explainsByRequest.keySet().iterator().next();
			explainsByRequest.remove(eldestRequestId);
		}
	}

	private record ExplainContext(String sessionId, String runtimeRequestId) {
	}

	private static final class ExplainAssembly {

		private String sessionId;

		private String runtimeRequestId;

		private String agentId;

		private String question;

		private String answer;

		private String datasource;

		private String sql;

		private String decisionReason;

		private String routeType;

		private String capabilityId;

		private final List<String> matchedMetrics = new ArrayList<>();

		private String resultScope;

		private final List<String> usedTables = new ArrayList<>();

		private final List<String> usedColumns = new ArrayList<>();

		private final List<Map<String, Object>> relationEvidence = new ArrayList<>();

		private final List<String> toolDecisionReasons = new ArrayList<>();

		private final List<String> resultScopeDetails = new ArrayList<>();

		private final List<SemanticHitView> semanticHits = new ArrayList<>();

		private final List<KnowledgeHitView> knowledgeHits = new ArrayList<>();

		private final List<ToolStepView> toolSteps = new ArrayList<>();

		private final Map<String, Object> clarify = new LinkedHashMap<>();

		private final Set<String> warnings = new LinkedHashSet<>();

		private long updatedAt;

		private AnswerTraceExplainView toView() {
			return AnswerTraceExplainView.builder()
				.sessionId(sessionId)
				.runtimeRequestId(runtimeRequestId)
				.agentId(agentId)
				.question(question)
				.answer(answer)
				.datasource(datasource)
				.sql(sql)
				.decisionReason(decisionReason)
				.routeType(routeType)
				.capabilityId(capabilityId)
				.matchedMetrics(List.copyOf(matchedMetrics))
				.resultScope(resultScope)
				.usedTables(List.copyOf(usedTables))
				.usedColumns(List.copyOf(usedColumns))
				.relationEvidence(List.copyOf(relationEvidence))
				.toolDecisionReasons(List.copyOf(toolDecisionReasons))
				.resultScopeDetails(List.copyOf(resultScopeDetails))
				.semanticHits(List.copyOf(semanticHits))
				.knowledgeHits(List.copyOf(knowledgeHits))
				.toolSteps(List.copyOf(toolSteps))
				.clarify(new LinkedHashMap<>(clarify))
				.warnings(List.copyOf(warnings))
				.updatedAt(updatedAt)
				.build();
		}

	}

	@Data
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AnswerTraceExplainView {

		private String sessionId;

		private String runtimeRequestId;

		private String agentId;

		private String question;

		private String answer;

		private String datasource;

		private String sql;

		private String decisionReason;

		private String routeType;

		private String capabilityId;

		@Builder.Default
		private List<String> matchedMetrics = List.of();

		private String resultScope;

		@Builder.Default
		private List<String> usedTables = List.of();

		@Builder.Default
		private List<String> usedColumns = List.of();

		@Builder.Default
		private List<Map<String, Object>> relationEvidence = List.of();

		@Builder.Default
		private List<String> toolDecisionReasons = List.of();

		@Builder.Default
		private List<String> resultScopeDetails = List.of();

		@Builder.Default
		private List<SemanticHitView> semanticHits = List.of();

		@Builder.Default
		private List<KnowledgeHitView> knowledgeHits = List.of();

		@Builder.Default
		private List<ToolStepView> toolSteps = List.of();

		@Builder.Default
		private Map<String, Object> clarify = Map.of();

		@Builder.Default
		private List<String> warnings = List.of();

		private long updatedAt;

	}

	@Data
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SemanticHitView {

		private String tableName;

		private String columnName;

		private String businessName;

		private String businessDescription;

		private String matchedBy;

		private Integer score;

		private String relationHint;

	}

	@Data
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class KnowledgeHitView {

		private String vectorType;

		private String knowledgeId;

		private String title;

		private String summary;

		private String snippet;

		private String source;

		private String concreteType;

	}

	@Data
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolStepView {

		private String toolName;

		private String title;

		private String summary;

		private String detail;

		private String datasource;

		private long timestampEpochMs;

	}

	@Data
	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ExplainMirrorSummary {

		private String datasource;

		private int semanticHitCount;

		private int knowledgeHitCount;

		private int toolStepCount;

	}

}
