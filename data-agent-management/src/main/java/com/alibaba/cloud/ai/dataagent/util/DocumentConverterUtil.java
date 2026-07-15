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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.bo.schema.ColumnInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.TableInfoBO;
import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.entity.AgentKnowledge;
import com.alibaba.cloud.ai.dataagent.entity.BusinessKnowledge;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricDefinition;
import com.alibaba.cloud.ai.dataagent.capability.metric.MetricApiParameter;
import lombok.extern.slf4j.Slf4j;                       
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for converting business objects to Document objects. Provides common
 * document conversion functionality for vector store operations.
 */
@Slf4j
public class DocumentConverterUtil {

	public static List<Document> convertColumnsToDocuments(Long agentId, Integer datasourceId,
			List<TableInfoBO> tables) {
		List<Document> documents = new ArrayList<>();
		for (TableInfoBO table : tables) {
			// 使用已经处理过的列数据，避免重复查询
			List<ColumnInfoBO> columns = table.getColumns();
			if (columns != null) {
				for (ColumnInfoBO column : columns) {
					documents.add(DocumentConverterUtil.convertColumnToDocument(agentId, datasourceId, table, column));
				}
			}
		}
		return documents;
	}

	/**
	 * Converts a column info object to a Document for vector storage.
	 * @param datasourceId the datasource ID
	 * @param tableInfoBO the table information containing schema details
	 * @param columnInfoBO the column information to convert
	 * @return Document object with column metadata
	 */
	public static Document convertColumnToDocument(Long agentId, Integer datasourceId, TableInfoBO tableInfoBO,
			ColumnInfoBO columnInfoBO) {
		String text = StringUtils.isBlank(columnInfoBO.getDescription()) ? columnInfoBO.getName()
				: columnInfoBO.getDescription();
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("name", columnInfoBO.getName());
		metadata.put("tableName", tableInfoBO.getName());
		metadata.put("description", Optional.ofNullable(columnInfoBO.getDescription()).orElse(""));
		metadata.put("type", columnInfoBO.getType());
		metadata.put("primary", columnInfoBO.isPrimary());
		metadata.put("notnull", columnInfoBO.isNotnull());
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.COLUMN);
		metadata.put(Constant.AGENT_ID, agentId.toString());
		metadata.put(Constant.DATASOURCE_ID, datasourceId.toString());

		if (columnInfoBO.getSamples() != null) {
			metadata.put("samples", columnInfoBO.getSamples());
		}

		return new Document(text, metadata);
	}

	/**
	 * Converts a table info object to a Document for vector storage.
	 * @param datasourceId the datasource ID
	 * @param tableInfoBO the table information to convert
	 * @return Document object with table metadata
	 */
	public static Document convertTableToDocument(Long agentId, Integer datasourceId, TableInfoBO tableInfoBO) {
		String text = StringUtils.isBlank(tableInfoBO.getDescription()) ? tableInfoBO.getName()
				: tableInfoBO.getDescription();
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("schema", Optional.ofNullable(tableInfoBO.getSchema()).orElse(""));
		metadata.put("name", tableInfoBO.getName());
		metadata.put("description", Optional.ofNullable(tableInfoBO.getDescription()).orElse(""));
		metadata.put("foreignKey", Optional.ofNullable(tableInfoBO.getForeignKey()).orElse(""));
		metadata.put("primaryKey", Optional.ofNullable(tableInfoBO.getPrimaryKeys()).orElse(new ArrayList<>()));
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE);
		metadata.put(Constant.AGENT_ID, agentId.toString());
		metadata.put(Constant.DATASOURCE_ID, datasourceId.toString());
		return new Document(text, metadata);
	}

	public static List<Document> convertTablesToDocuments(Long agentId, Integer datasourceId,
			List<TableInfoBO> tables) {
		return tables.stream()
			.map(table -> DocumentConverterUtil.convertTableToDocument(agentId, datasourceId, table))
			.collect(Collectors.toList());
	}

	public static Document convertBusinessKnowledgeToDocument(BusinessKnowledge businessKnowledge) {

		// 构建文档内容，包含业务名词、说明和同义词
		String businessTerm = businessKnowledge.getBusinessTerm();
		String description = Optional.ofNullable(businessKnowledge.getDescription()).orElse("无");
		String synonyms = Optional.ofNullable(businessKnowledge.getSynonyms()).orElse("无");

		String content = String.format("业务名词: %s, 说明: %s, 同义词: %s", businessTerm, description, synonyms);

		// 构建元数据
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.BUSINESS_TERM);
		metadata.put(Constant.AGENT_ID, businessKnowledge.getAgentId().toString());
		metadata.put(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, businessKnowledge.getId());

		return new Document(content, metadata);
	}

	public static Document convertMetricToDocument(String agentId, MetricDefinition definition) {
		StringBuilder content = new StringBuilder();
		content.append("指标编码: ").append(definition.metricCode()).append("\n");
		content.append("指标名称: ").append(definition.metricName()).append("\n");
		if (definition.summary() != null) {
			content.append("接口描述: ").append(definition.summary()).append("\n");
		}
		if (definition.description() != null) {
			content.append("详细说明: ").append(definition.description()).append("\n");
		}
		if (definition.aliases() != null && !definition.aliases().isEmpty()) {
			content.append("别名: ").append(String.join(", ", definition.aliases())).append("\n");
		}
		if (definition.tags() != null && !definition.tags().isEmpty()) {
			content.append("标签: ").append(String.join(", ", definition.tags())).append("\n");
		}
		List<MetricApiParameter> params = definition.requestParameters();
		if (params != null && !params.isEmpty()) {
			content.append("可用参数: ");
			List<String> paramDescs = new java.util.ArrayList<>();
			for (MetricApiParameter p : params) {
				StringBuilder pd = new StringBuilder(p.name());
				if (p.description() != null) {
					pd.append("(").append(p.description()).append(")");
				}
				if (p.enumValues() != null && !p.enumValues().isEmpty()) {
					pd.append("[").append(String.join("/", p.enumValues())).append("]");
				}
				paramDescs.add(pd.toString());
			}
			content.append(String.join(", ", paramDescs)).append("\n");
		}
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.METRIC);
		metadata.put(Constant.AGENT_ID, agentId);
		metadata.put(DocumentMetadataConstant.METRIC_CODE, definition.metricCode());
		metadata.put(DocumentMetadataConstant.OPERATION_ID, definition.operationId());
		return new Document(content.toString(), metadata);
	}

	public static Document convertQaFaqKnowledgeToDocument(AgentKnowledge knowledge) {
		String title = Optional.ofNullable(knowledge.getTitle()).orElse("");
		String question = Optional.ofNullable(knowledge.getQuestion()).orElse("");
		String answer = Optional.ofNullable(knowledge.getContent()).orElse("");
		String content = String.format("标题: %s\n问题: %s\n答案: %s", title, question, answer).trim();
		Map<String, Object> metadata = new HashMap<>();
		// answer和isRecall经常变更的放到关系数据库
		metadata.put(Constant.AGENT_ID, knowledge.getAgentId().toString());
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.AGENT_KNOWLEDGE);
		metadata.put(DocumentMetadataConstant.DB_AGENT_KNOWLEDGE_ID, knowledge.getId());
		metadata.put(DocumentMetadataConstant.CONCRETE_AGENT_KNOWLEDGE_TYPE, knowledge.getType().getCode());

		return new Document(content, metadata);
	}

	/**
	 * 为文档列表添加元数据，用于DOCUMENT类型知识处理
	 * @param documents 原始文档列表
	 * @param knowledge 知识对象
	 * @return 添加了元数据的文档列表
	 */
	public static List<Document> convertAgentKnowledgeDocumentsWithMetadata(List<Document> documents,
			AgentKnowledge knowledge) {
		List<Document> documentsWithMetadata = new ArrayList<>();

		for (Document doc : documents) {
			// isRecall经常变更的放到关系数据库不放metadata中
			// 创建元数据
			Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
			metadata.put(Constant.AGENT_ID, knowledge.getAgentId().toString());
			metadata.put(DocumentMetadataConstant.DB_AGENT_KNOWLEDGE_ID, knowledge.getId());
			metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.AGENT_KNOWLEDGE);
			metadata.put(DocumentMetadataConstant.CONCRETE_AGENT_KNOWLEDGE_TYPE, knowledge.getType().getCode());

			// 创建带有元数据的新文档
			Document docWithMetadata = new Document(doc.getId(), doc.getText(), metadata);
			documentsWithMetadata.add(docWithMetadata);
		}
		return documentsWithMetadata;
	}

	/**
	 * Private constructor to prevent instantiation.
	 */
	private DocumentConverterUtil() {
		throw new AssertionError("Cannot instantiate utility class");
	}

}
