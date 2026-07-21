<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->
<script setup lang="ts">
  import { computed, reactive, ref } from 'vue';
  import { CopyDocument as ICON_COPY, View as ICON_VIEW } from '@element-plus/icons-vue';
  import { ElMessage, ElTag } from 'element-plus';
  import { TextType, type AgentResponse } from '@/services/graph';

  const props = defineProps<{
    nodeName: string;
    nodeBlock: AgentResponse[];
  }>();

  const toolName = computed(() => {
    return props.nodeName.startsWith('tool:') ? props.nodeName.substring(5) : props.nodeName;
  });

  const collapsedSections = reactive<Record<string, boolean>>({});

  const sectionDefaults: Record<string, boolean> = {
    'ds.tables': true,
    'ds.columns': true,
    'ds.rows': true,
    'ds.relations': true,
    'ds.sql': true,
    'sg.problems': true,
    'sg.fixes': true,
    'sg.rules': true,
    'sg.profiles': true,
    'mt.basic': true,
    'mt.params': true,
    'mt.dims': true,
    'mt.filters': true,
    'mt.candidates': true,
    'mt.rows': true,
    'sm.hits': true,
    'dk.hits': true,
  };

  function toggleSection(key: string) {
    if (collapsedSections[key] === undefined) {
      collapsedSections[key] = sectionDefaults[key] ?? true;
    }
    collapsedSections[key] = !collapsedSections[key];
  }

  function isCollapsed(key: string): boolean {
    if (collapsedSections[key] === undefined) {
      collapsedSections[key] = sectionDefaults[key] ?? true;
    }
    return collapsedSections[key];
  }

  const cardCollapsed = ref(true);

  function toggleCard() {
    cardCollapsed.value = !cardCollapsed.value;
  }

  interface ToolData {
    category: string;
    json: any;
    jsonText: string;
    parseError?: string;
    waiting: boolean;
  }

  const toolData = computed<ToolData>(() => {
    const jsonItems = props.nodeBlock.filter(
      (item) => item.textType === TextType.JSON && item.text && item.text.trim(),
    );
    let jsonText = '';
    if (jsonItems.length > 0) {
      jsonText = jsonItems[jsonItems.length - 1].text;
    } else {
      const textItems = props.nodeBlock.filter(
        (item) =>
          item.textType === TextType.TEXT && item.text && !item.text.startsWith('Calling tool:'),
      );
      jsonText = textItems.map((item) => item.text).join('\n');
    }

    const name = toolName.value;
    let category = 'unknown';
    if (name.startsWith('datasource.')) {
      category = 'datasource';
    } else if (name === 'sql_guard.check') {
      category = 'sql_guard';
    } else if (name === 'semantic_model.search') {
      category = 'semantic_model';
    } else if (name === 'domain_business_knowledge.search') {
      category = 'domain_knowledge';
    } else if (name.startsWith('metric.')) {
      category = 'metric';
    } else if (name.startsWith('skill.')) {
      category = 'skill';
    }

    if (!jsonText || jsonText.trim().length === 0) {
      return { category, json: null, jsonText: '', waiting: true };
    }

    const trimmed = jsonText.trim();
    if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) {
      return {
        category,
        json: null,
        jsonText,
        waiting: false,
        parseError: '工具返回非 JSON 格式，降级显示原文',
      };
    }

    try {
      const json = JSON.parse(jsonText);
      return { category, json, jsonText, waiting: false };
    } catch {
      return { category, json: null, jsonText, waiting: false, parseError: 'JSON 解析失败，降级显示原文' };
    }
  });

  const displayTitle = computed(() => {
    const name = toolName.value;
    if (name === 'sql_guard.check') return 'SQL 安全校验';
    if (name === 'semantic_model.search') return '语义模型检索';
    if (name === 'domain_business_knowledge.search') return '业务知识检索';
    if (name.startsWith('datasource.')) return '数据源探查';
    if (name.startsWith('metric.')) return '指标查询';
    if (name.startsWith('skill.')) return '内置技能';
    return name;
  });

  function isDatasourceResult(data: any): boolean {
    return data && typeof data === 'object' && 'action' in data && 'tables' in data;
  }

  function isSqlGuardResult(data: any): boolean {
    return (
      data && typeof data === 'object' && 'decision' in data && ('problems' in data || 'ruleChecks' in data)
    );
  }

  function isSemanticResult(data: any): boolean {
    return data && typeof data === 'object' && 'hits' in data && Array.isArray(data.hits);
  }

  function isKnowledgeResult(data: any): boolean {
    return data && typeof data === 'object' && 'hits' in data && Array.isArray(data.hits);
  }

  function isMetricDescribe(data: any): boolean {
    return (
      data &&
      typeof data === 'object' &&
      'metricCode' in data &&
      !('candidates' in data) &&
      !('status' in data) &&
      'requestParameters' in data
    );
  }

  function copyJson() {
    try {
      const allJson = props.nodeBlock
        .filter((item) => item.textType === TextType.JSON && item.text && item.text.trim())
        .map((item) => item.text)
        .join('\n');
      const raw = allJson || toolData.value.jsonText;
      navigator.clipboard
        .writeText(raw)
        .then(() => ElMessage.success('已复制到剪贴板'))
        .catch(() => ElMessage.error('复制失败'));
    } catch {
      ElMessage.error('复制失败');
    }
  }

  function getDecisionTagType(decision: string): 'success' | 'warning' | 'danger' | 'info' {
    if (!decision) return 'info';
    const d = decision.toLowerCase();
    if (d.includes('safe')) return 'success';
    if (d.includes('revise') || d.includes('inspect')) return 'warning';
    return 'info';
  }

  function getDecisionLabel(decision: string): string {
    if (!decision) return '未知';
    const d = decision.toLowerCase();
    if (d.includes('safe')) return '可执行';
    if (d.includes('revise')) return '需修订';
    if (d.includes('inspect')) return '需检查';
    return decision;
  }

  function getSeverityTagType(severity: string): 'danger' | 'warning' | 'info' {
    if (!severity) return 'info';
    const s = severity.toLowerCase();
    if (s === 'error' || s === 'critical') return 'danger';
    if (s === 'warning' || s === 'warn') return 'warning';
    return 'info';
  }

  function formatValue(val: any): string {
    if (val === null || val === undefined) return '-';
    if (typeof val === 'object') return JSON.stringify(val);
    return String(val);
  }
</script>

<template>
  <div class="tool-result-block">
    <div class="tool-result-title" :class="{ collapsed: cardCollapsed }" @click="toggleCard">
      <span class="tool-result-title-text">{{ displayTitle }} — {{ toolName }}</span>
      <div class="tool-result-title-actions">
        <el-button class="copy-btn" text size="small" @click.stop="copyJson">
          <el-icon size="14"><ICON_COPY /></el-icon>
          复制原始数据
        </el-button>
      </div>
    </div>
    <div v-show="!cardCollapsed" class="tool-result-body">
      <div v-if="toolData.waiting" class="tool-waiting">
        <span class="tool-spinner"></span>
        <span>等待工具结果...</span>
      </div>

      <div v-else-if="toolData.parseError" class="tool-parse-error">
        <p>{{ toolData.parseError }}</p>
        <pre class="tool-raw-text">{{ toolData.jsonText }}</pre>
      </div>

      <!-- datasource.*.search -->
      <template v-else-if="toolData.category === 'datasource' && isDatasourceResult(toolData.json)">
        <div class="tool-section">
          <div class="tool-summary">
            <el-tag size="small" type="info">{{ toolData.json.action }}</el-tag>
            <span class="tool-summary-text">{{ toolData.json.summary }}</span>
            <span v-if="toolData.json.datasource" class="tool-meta">
              数据源: {{ toolData.json.datasource }}
            </span>
          </div>

          <!-- tables -->
          <div v-if="toolData.json.tables && toolData.json.tables.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('ds.tables') }"
              @click="toggleSection('ds.tables')"
            >
              数据表
              <el-tag size="small" round>{{ toolData.json.tables.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('ds.tables')">
            <div class="tool-table-cards">
              <div
                v-for="table in toolData.json.tables"
                :key="table.name"
                class="tool-table-card"
              >
                <div class="tool-table-card-header">
                  <el-icon size="16"><ICON_VIEW /></el-icon>
                  <span class="tool-table-name">{{ table.name }}</span>
                  <el-tag v-if="table.selected !== false" size="small" type="success"
                    >已选中</el-tag
                  >
                </div>
                <div v-if="table.description" class="tool-table-desc">
                  {{ table.description }}
                </div>
                <div v-if="table.schema" class="tool-table-schema">Schema: {{ table.schema }}</div>
                <div class="tool-table-meta">
                  <span v-if="table.primaryKeys && table.primaryKeys.length > 0">
                    主键:
                    <el-tag
                      v-for="pk in table.primaryKeys"
                      :key="pk"
                      size="small"
                      effect="plain"
                      type="warning"
                    >
                      {{ pk }}
                    </el-tag>
                  </span>
                  <span v-if="table.foreignKeys" class="tool-table-fk">
                    外键: {{ table.foreignKeys }}
                  </span>
                </div>
              </div>
            </div>
            </div>
          </div>

          <!-- columns -->
          <div v-if="toolData.json.columns && toolData.json.columns.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('ds.columns') }"
              @click="toggleSection('ds.columns')"
            >
              列信息
              <el-tag size="small" round>{{ toolData.json.columns.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('ds.columns')">
            <div class="tool-columns-table-wrap">
              <table class="tool-columns-table">
                <thead>
                  <tr>
                    <th>列名</th>
                    <th>类型</th>
                    <th>描述</th>
                    <th>属性</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="col in toolData.json.columns" :key="col.name">
                    <td class="col-name">{{ col.name }}</td>
                    <td>
                      <el-tag size="small" effect="plain">{{ col.type || '-' }}</el-tag>
                    </td>
                    <td class="col-desc">{{ col.description || '-' }}</td>
                    <td>
                      <el-tag v-if="col.primaryKey" size="small" type="warning" effect="plain"
                        >主键</el-tag
                      >
                      <el-tag v-if="col.notNull" size="small" type="danger" effect="plain"
                        >非空</el-tag
                      >
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            </div>
          </div>

          <!-- rows -->
          <div v-if="toolData.json.rows && toolData.json.rows.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('ds.rows') }"
              @click="toggleSection('ds.rows')"
            >
              数据预览
              <el-tag size="small" round>{{ toolData.json.rows.length }} 行</el-tag>
            </h4>
            <div v-show="!isCollapsed('ds.rows')">
            <div class="tool-rows-table-wrap">
              <table class="tool-rows-table">
                <thead>
                  <tr>
                    <th v-for="key in Object.keys(toolData.json.rows[0])" :key="key">
                      {{ key }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, ri) in toolData.json.rows" :key="ri">
                    <td v-for="key in Object.keys(toolData.json.rows[0])" :key="key">
                      {{ formatValue(row[key]) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            </div>
          </div>

          <!-- relations -->
          <div v-if="toolData.json.relations && toolData.json.relations.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('ds.relations') }"
              @click="toggleSection('ds.relations')"
            >
              表关系
              <el-tag size="small" round>{{ toolData.json.relations.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('ds.relations')">
            <div class="tool-relations">
              <div v-for="(rel, ri) in toolData.json.relations" :key="ri" class="tool-relation-item">
                <el-tag size="small" type="info">{{ rel.sourceTable }}</el-tag>
                <span class="tool-relation-arrow">→</span>
                <el-tag size="small" type="info">{{ rel.targetTable }}</el-tag>
                <span v-if="rel.joinCondition" class="tool-relation-cond">
                  {{ rel.joinCondition }}
                </span>
              </div>
            </div>
            </div>
          </div>

          <!-- sql -->
          <div v-if="toolData.json.sql" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('ds.sql') }"
              @click="toggleSection('ds.sql')"
            >
              执行的 SQL
            </h4>
            <div v-show="!isCollapsed('ds.sql')">
              <pre class="tool-sql-block"><code>{{ toolData.json.sql }}</code></pre>
            </div>
          </div>
        </div>
      </template>

      <!-- sql_guard.check -->
      <template v-else-if="toolData.category === 'sql_guard' && isSqlGuardResult(toolData.json)">
        <div class="tool-section">
          <!-- 决策头部 -->
          <div class="tool-decision-bar">
            <el-tag
              :type="getDecisionTagType(toolData.json.decision)"
              size="large"
              effect="dark"
            >
              {{ getDecisionLabel(toolData.json.decision) }}
            </el-tag>
            <span class="tool-summary-text">{{ toolData.json.summary }}</span>
          </div>

          <!-- 对齐状态 -->
          <div v-if="toolData.json.isAligned !== undefined" class="tool-aligned">
            用户意图对齐:
            <el-tag :type="toolData.json.isAligned ? 'success' : 'danger'" size="small">
              {{ toolData.json.isAligned ? '已对齐' : '未对齐' }}
            </el-tag>
          </div>

          <!-- 问题列表 -->
          <div v-if="toolData.json.problems && toolData.json.problems.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('sg.problems') }"
              @click="toggleSection('sg.problems')"
            >
              发现问题
              <el-tag size="small" round type="danger">{{ toolData.json.problems.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('sg.problems')">
            <div class="tool-problems">
              <div
                v-for="(prob, pi) in toolData.json.problems"
                :key="pi"
                class="tool-problem-item"
              >
                <div class="tool-problem-header">
                  <el-tag
                    :type="getSeverityTagType(prob.severity)"
                    size="small"
                    effect="dark"
                  >
                    {{ prob.severity || '未知' }}
                  </el-tag>
                  <span class="tool-problem-code">{{ prob.code }}</span>
                  <span class="tool-problem-title">{{ prob.title }}</span>
                </div>
                <div v-if="prob.message" class="tool-problem-message">{{ prob.message }}</div>
                <div v-if="prob.why" class="tool-problem-why">
                  <strong>原因:</strong> {{ prob.why }}
                </div>
                <div class="tool-problem-detail" v-if="prob.expected || prob.actual || prob.evidence">
                  <div v-if="prob.expected">
                    <strong>预期:</strong> {{ prob.expected }}
                  </div>
                  <div v-if="prob.actual">
                    <strong>实际:</strong> {{ prob.actual }}
                  </div>
                  <div v-if="prob.evidence">
                    <strong>证据:</strong> {{ prob.evidence }}
                  </div>
                </div>
                <div v-if="prob.repairHint" class="tool-problem-repair">
                  <strong>修复建议:</strong> {{ prob.repairHint }}
                </div>
              </div>
            </div>
            </div>
          </div>

          <!-- 修复建议 -->
          <div
            v-if="toolData.json.fixSuggestions && toolData.json.fixSuggestions.length > 0"
            class="tool-subsection"
          >
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('sg.fixes') }"
              @click="toggleSection('sg.fixes')"
            >
              修复建议
              <el-tag size="small" round>{{ toolData.json.fixSuggestions.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('sg.fixes')">
              <ul class="tool-fix-list">
                <li v-for="(fix, fi) in toolData.json.fixSuggestions" :key="fi">{{ fix }}</li>
              </ul>
            </div>
          </div>

          <!-- 规则检查 -->
          <div v-if="toolData.json.ruleChecks && toolData.json.ruleChecks.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('sg.rules') }"
              @click="toggleSection('sg.rules')"
            >
              规则检查
              <el-tag size="small" round>{{ toolData.json.ruleChecks.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('sg.rules')">
            <div class="tool-rule-checks">
              <div
                v-for="(rule, ri) in toolData.json.ruleChecks"
                :key="ri"
                class="tool-rule-item"
              >
                <el-tag
                  :type="rule.status === 'PASSED' ? 'success' : 'danger'"
                  size="small"
                  effect="plain"
                >
                  {{ rule.status === 'PASSED' ? '通过' : '未通过' }}
                </el-tag>
                <span class="tool-rule-detail">{{ rule.detail }}</span>
                <span v-if="rule.evidence" class="tool-rule-evidence">
                  {{ rule.evidence }}
                </span>
              </div>
            </div>
            </div>
          </div>

          <!-- 列分析 -->
          <div v-if="toolData.json.columnProfiles && toolData.json.columnProfiles.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('sg.profiles') }"
              @click="toggleSection('sg.profiles')"
            >
              列数据画像
              <el-tag size="small" round>{{ toolData.json.columnProfiles.length }}</el-tag>
              <span v-if="toolData.json.totalRows" class="tool-profile-total">
                (共 {{ toolData.json.totalRows }} 行)
              </span>
            </h4>
            <div v-show="!isCollapsed('sg.profiles')">
            <div v-for="(prof, pi) in toolData.json.columnProfiles" :key="pi" class="tool-profile-item">
              <h5 class="tool-profile-col-name">{{ prof.columnName || '未命名列' }}</h5>
              <div class="tool-profile-stats">
                <span v-if="prof.nullRate !== undefined">
                  空值率: <strong>{{ (prof.nullRate * 100).toFixed(1) }}%</strong>
                </span>
                <span v-if="prof.distinctCount !== undefined">
                  去重数: <strong>{{ prof.distinctCount }}</strong>
                </span>
                <span v-if="prof.minValue !== undefined && prof.maxValue !== undefined">
                  范围: <strong>{{ prof.minValue }} ~ {{ prof.maxValue }}</strong>
                </span>
              </div>
              <div v-if="prof.topValues && prof.topValues.length > 0" class="tool-profile-top">
                <span class="tool-profile-label">高频值:</span>
                <el-tag
                  v-for="(tv, tvi) in prof.topValues.slice(0, 10)"
                  :key="tvi"
                  size="small"
                  effect="plain"
                >
                  {{ tv.value }} ({{ tv.count }})
                </el-tag>
              </div>
              <div v-if="prof.profileHints && prof.profileHints.length > 0" class="tool-profile-hints">
                <span class="tool-profile-label">提示:</span>
                <span v-for="(hint, hi) in prof.profileHints" :key="hi" class="tool-profile-hint">
                  {{ hi > 0 ? '; ' : '' }}{{ hint }}
                </span>
              </div>
            </div>
            </div>
          </div>
        </div>
      </template>

      <!-- semantic_model.search -->
      <template v-else-if="toolData.category === 'semantic_model' && isSemanticResult(toolData.json)">
        <div class="tool-section">
          <div class="tool-summary">
            <span class="tool-summary-text">{{ toolData.json.summary }}</span>
          </div>
          <div v-if="toolData.json.resolution" class="tool-resolution">
            {{ toolData.json.resolution }}
          </div>
          <div
            v-if="toolData.json.hits && toolData.json.hits.length > 0"
            class="tool-subsection"
          >
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('sm.hits') }"
              @click="toggleSection('sm.hits')"
            >
              检索命中
              <el-tag size="small" round>{{ toolData.json.hits.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('sm.hits')">
            <div class="tool-hits">
              <div v-for="(hit, hi) in toolData.json.hits" :key="hi" class="tool-hit-item">
                <div class="tool-hit-header">
                  <span class="tool-hit-table">{{ hit.tableName }}</span>
                  <span class="tool-hit-col">{{ hit.columnName }}</span>
                  <el-tag size="small" type="success" effect="plain">
                    {{ hit.businessName || hit.columnName }}
                  </el-tag>
                  <span v-if="hit.score !== undefined" class="tool-hit-score">
                    匹配度: {{ (hit.score * 100).toFixed(0) }}%
                  </span>
                </div>
                <div v-if="hit.businessDescription" class="tool-hit-desc">
                  {{ hit.businessDescription }}
                </div>
                <div class="tool-hit-meta">
                  <span v-if="hit.dataType">
                    <el-tag size="small" effect="plain" type="info">{{ hit.dataType }}</el-tag>
                  </span>
                  <span v-if="hit.synonyms && hit.synonyms.length > 0">
                    同义词: {{ hit.synonyms.join(', ') }}
                  </span>
                  <span v-if="hit.relationHint">关系: {{ hit.relationHint }}</span>
                  <span v-if="hit.matchedBy">匹配方式: {{ hit.matchedBy }}</span>
                </div>
              </div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- domain_business_knowledge.search -->
      <template v-else-if="toolData.category === 'domain_knowledge' && isKnowledgeResult(toolData.json)">
        <div class="tool-section">
          <div v-if="toolData.json.hits && toolData.json.hits.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('dk.hits') }"
              @click="toggleSection('dk.hits')"
            >
              知识命中
              <el-tag size="small" round>{{ toolData.json.hits.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('dk.hits')">
            <div class="tool-hits">
              <div v-for="(hit, hi) in toolData.json.hits" :key="hi" class="tool-hit-item">
                <div class="tool-hit-header">
                  <el-tag size="small" type="primary" effect="plain">
                    {{ hit.vectorType || '未知类型' }}
                  </el-tag>
                  <span class="tool-hit-title">{{ hit.title || '无标题' }}</span>
                </div>
                <div v-if="hit.summary" class="tool-hit-desc">{{ hit.summary }}</div>
                <div v-if="hit.snippet" class="tool-hit-snippet">{{ hit.snippet }}</div>
                <div v-if="hit.source" class="tool-hit-source">来源: {{ hit.source }}</div>
              </div>
            </div>
            </div>
          </div>
          <div v-if="toolData.json.resolution" class="tool-resolution">
            {{ toolData.json.resolution }}
          </div>
          <div
            v-if="toolData.json.warnings && toolData.json.warnings.length > 0"
            class="tool-warnings"
          >
            <span v-for="(w, wi) in toolData.json.warnings" :key="wi" class="tool-warning-item">
              {{ w }}
            </span>
          </div>
        </div>
      </template>

      <!-- metric.*.* -->
      <template v-else-if="toolData.category === 'metric'">
        <div class="tool-section">
          <div v-if="toolData.json.summary" class="tool-summary">
            <span class="tool-summary-text">{{ toolData.json.summary }}</span>
          </div>
          <div v-if="toolData.json.status" class="tool-metric-status">
            状态:
            <el-tag
              :type="
                toolData.json.status === 'SUCCESS'
                  ? 'success'
                  : toolData.json.status === 'FALLBACK_TO_DB'
                    ? 'warning'
                    : 'info'
              "
              size="small"
            >
              {{ toolData.json.status }}
            </el-tag>
          </div>

          <!-- candidates (metric.catalog.search) -->
          <div
            v-if="toolData.json.candidates && toolData.json.candidates.length > 0"
            class="tool-subsection"
          >
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('mt.candidates') }"
              @click="toggleSection('mt.candidates')"
            >
              候选指标
              <el-tag size="small" round>{{ toolData.json.candidates.length }}</el-tag>
            </h4>
            <div v-show="!isCollapsed('mt.candidates')">
            <div class="tool-hits">
              <div v-for="(c, ci) in toolData.json.candidates" :key="ci" class="tool-hit-item">
                <div class="tool-hit-header">
                  <span class="tool-hit-table">{{ c.metricCode || c.operationId || c.apiId }}</span>
                  <span v-if="c.score !== undefined" class="tool-hit-score">
                    匹配度: {{ (c.score * 100).toFixed(0) }}%
                  </span>
                </div>
                <div v-if="c.content" class="tool-hit-desc">{{ c.content }}</div>
              </div>
            </div>
            </div>
          </div>

          <!-- rows (metric.query.execute) -->
          <div v-if="toolData.json.rows && toolData.json.rows.length > 0" class="tool-subsection">
            <h4
              class="tool-subsection-title collapsible"
              :class="{ collapsed: isCollapsed('mt.rows') }"
              @click="toggleSection('mt.rows')"
            >
              查询结果
              <el-tag size="small" round>{{ toolData.json.rows.length }} 行</el-tag>
            </h4>
            <div v-show="!isCollapsed('mt.rows')">
            <div class="tool-rows-table-wrap">
              <table class="tool-rows-table">
                <thead>
                  <tr>
                    <th v-for="key in Object.keys(toolData.json.rows[0])" :key="key">
                      {{ key }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, ri) in toolData.json.rows" :key="ri">
                    <td v-for="key in Object.keys(toolData.json.rows[0])" :key="key">
                      {{ formatValue(row[key]) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            </div>
          </div>

          <!-- describe (metric.catalog.describe) -->
          <template v-else-if="isMetricDescribe(toolData.json)">
            <div v-if="toolData.json.metricCode" class="tool-subsection">
              <h4
                class="tool-subsection-title collapsible"
                :class="{ collapsed: isCollapsed('mt.basic') }"
                @click="toggleSection('mt.basic')"
              >
                基本信息
              </h4>
              <div v-show="!isCollapsed('mt.basic')" class="tool-metric-basic">
                <div class="tool-metric-row">
                  <span class="tool-metric-label">标识</span>
                  <code>{{ toolData.json.metricCode }}</code>
                </div>
                <div v-if="toolData.json.metricName" class="tool-metric-row">
                  <span class="tool-metric-label">名称</span>
                  <span>{{ toolData.json.metricName }}</span>
                </div>
                <div v-if="toolData.json.description" class="tool-metric-row">
                  <span class="tool-metric-label">描述</span>
                  <span>{{ toolData.json.description }}</span>
                </div>
                <div v-if="toolData.json.operationId" class="tool-metric-row">
                  <span class="tool-metric-label">接口</span>
                  <code>{{ toolData.json.httpMethod || 'GET' }} {{ toolData.json.path }}</code>
                  <span class="tool-metric-opid">({{ toolData.json.operationId }})</span>
                </div>
                <div v-if="toolData.json.tags && toolData.json.tags.length > 0" class="tool-metric-row">
                  <span class="tool-metric-label">标签</span>
                  <el-tag v-for="t in toolData.json.tags" :key="t" size="small" effect="plain">{{ t }}</el-tag>
                </div>
              </div>
            </div>

            <div v-if="toolData.json.requestParameters && toolData.json.requestParameters.length > 0" class="tool-subsection">
              <h4
                class="tool-subsection-title collapsible"
                :class="{ collapsed: isCollapsed('mt.params') }"
                @click="toggleSection('mt.params')"
              >
                请求参数
                <el-tag size="small" round>{{ toolData.json.requestParameters.length }}</el-tag>
              </h4>
              <div v-show="!isCollapsed('mt.params')">
                <div class="tool-columns-table-wrap">
                  <table class="tool-columns-table">
                    <thead>
                      <tr>
                        <th>参数名</th>
                        <th>类型</th>
                        <th>必填</th>
                        <th>描述</th>
                        <th>默认值</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="p in toolData.json.requestParameters" :key="p.name">
                        <td class="col-name">{{ p.name }}</td>
                        <td><el-tag size="small" effect="plain">{{ p.type || '-' }}</el-tag></td>
                        <td>
                          <el-tag :type="p.required ? 'danger' : 'info'" size="small" effect="plain">
                            {{ p.required ? '必填' : '可选' }}
                          </el-tag>
                        </td>
                        <td class="col-desc">{{ p.description || '-' }}</td>
                        <td>{{ p.defaultValue !== undefined ? p.defaultValue : '-' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>

            <div v-if="toolData.json.supportedDimensions && toolData.json.supportedDimensions.length > 0" class="tool-subsection">
              <h4
                class="tool-subsection-title collapsible"
                :class="{ collapsed: isCollapsed('mt.dims') }"
                @click="toggleSection('mt.dims')"
              >
                支持维度
                <el-tag size="small" round>{{ toolData.json.supportedDimensions.length }}</el-tag>
              </h4>
              <div v-show="!isCollapsed('mt.dims')">
                <div class="tool-relations">
                  <el-tag v-for="d in toolData.json.supportedDimensions" :key="d" size="small" effect="plain" type="success">{{ d }}</el-tag>
                </div>
              </div>
            </div>

            <div v-if="toolData.json.supportedFilters && toolData.json.supportedFilters.length > 0" class="tool-subsection">
              <h4
                class="tool-subsection-title collapsible"
                :class="{ collapsed: isCollapsed('mt.filters') }"
                @click="toggleSection('mt.filters')"
              >
                支持过滤
                <el-tag size="small" round>{{ toolData.json.supportedFilters.length }}</el-tag>
              </h4>
              <div v-show="!isCollapsed('mt.filters')">
                <div class="tool-relations">
                  <el-tag v-for="f in toolData.json.supportedFilters" :key="f" size="small" effect="plain" type="warning">{{ f }}</el-tag>
                </div>
              </div>
            </div>
          </template>

          <!-- else: raw JSON -->
          <div v-else class="tool-raw-json">
            <pre>{{ JSON.stringify(toolData.json, null, 2) }}</pre>
          </div>
        </div>
      </template>

      <!-- skill.current_time.now etc. -->
      <template v-else-if="toolData.category === 'skill'">
        <div class="tool-section">
          <div v-if="toolData.json.formattedTime" class="tool-time-display">
            <div class="tool-time-main">{{ toolData.json.formattedTime }}</div>
            <div class="tool-time-iso">ISO: {{ toolData.json.isoTime }}</div>
            <div class="tool-time-tz">时区: {{ toolData.json.timezone }}</div>
          </div>
          <div v-else class="tool-raw-json">
            <pre>{{ JSON.stringify(toolData.json, null, 2) }}</pre>
          </div>
        </div>
      </template>

      <!-- unknown / fallback: formatted JSON -->
      <template v-else>
        <div class="tool-section">
          <pre class="tool-raw-json">{{ JSON.stringify(toolData.json, null, 2) }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
  .tool-result-block {
    background: #f8f9fa;
    border: 1px solid #e8e8e8;
    border-radius: 8px;
    overflow: hidden;
    transition: all 0.3s ease;
    margin-bottom: 16px;
  }

  .tool-result-block:hover {
    border-color: #409eff;
    box-shadow: 0 2px 8px rgba(64, 158, 255, 0.1);
  }

  .tool-result-title {
    background: #ecf5ff;
    padding: 12px 16px;
    font-weight: 600;
    color: #409eff;
    border-bottom: 1px solid #e8e8e8;
    font-size: 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    cursor: pointer;
    user-select: none;
  }

  .tool-result-title:hover {
    background: #d9ecff;
  }

  .tool-result-title-text::before {
    content: '▾';
    display: inline-block;
    margin-right: 8px;
    font-size: 12px;
    transition: transform 0.2s ease;
    color: #909399;
  }

  .tool-result-title.collapsed .tool-result-title-text::before {
    transform: rotate(-90deg);
  }

  .tool-result-title-actions {
    display: flex;
    align-items: center;
  }

  .copy-btn {
    color: #909399;
    font-weight: 400;
  }

  .copy-btn:hover {
    color: #409eff;
  }

  .tool-result-body {
    padding: 16px;
    line-height: 1.6;
    font-size: 14px;
  }

  .tool-section {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .tool-summary {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .tool-summary-text {
    color: #303133;
    font-weight: 500;
  }

  .tool-meta {
    color: #909399;
    font-size: 12px;
    margin-left: auto;
  }

  .tool-resolution {
    color: #606266;
    padding: 8px 12px;
    background: #f0f9eb;
    border-radius: 4px;
    border-left: 3px solid #67c23a;
  }

  .tool-warnings {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .tool-warning-item {
    color: #e6a23c;
    font-size: 13px;
    padding: 4px 8px;
    background: #fdf6ec;
    border-radius: 4px;
    border-left: 2px solid #e6a23c;
  }

  .tool-subsection {
    margin-top: 8px;
  }

  .tool-subsection-title {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    margin: 0 0 8px 0;
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .tool-subsection-title.collapsible {
    cursor: pointer;
    user-select: none;
  }

  .tool-subsection-title.collapsible::before {
    content: '▾';
    display: inline-block;
    font-size: 12px;
    transition: transform 0.2s ease;
    color: #909399;
    line-height: 1;
  }

  .tool-subsection-title.collapsible.collapsed::before {
    transform: rotate(-90deg);
  }

  .tool-subsection-title.collapsible:hover {
    color: #409eff;
  }

  /* table cards */
  .tool-table-cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 8px;
  }

  .tool-table-card {
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
    padding: 12px;
    transition: border-color 0.2s;
  }

  .tool-table-card:hover {
    border-color: #409eff;
  }

  .tool-table-card-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }

  .tool-table-name {
    font-weight: 600;
    color: #303133;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }

  .tool-table-desc {
    color: #606266;
    font-size: 13px;
    margin-bottom: 4px;
  }

  .tool-table-schema {
    color: #909399;
    font-size: 12px;
  }

  .tool-table-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 6px;
    font-size: 12px;
    color: #909399;
  }

  .tool-table-fk {
    color: #606266;
    font-size: 12px;
    word-break: break-all;
  }

  /* columns table */
  .tool-columns-table-wrap,
  .tool-rows-table-wrap {
    overflow-x: auto;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
  }

  .tool-columns-table,
  .tool-rows-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }

  .tool-columns-table th,
  .tool-rows-table th {
    background: #f5f7fa;
    padding: 8px 12px;
    text-align: left;
    font-weight: 600;
    color: #303133;
    border-bottom: 1px solid #e4e7ed;
    white-space: nowrap;
  }

  .tool-columns-table td,
  .tool-rows-table td {
    padding: 6px 12px;
    border-bottom: 1px solid #ebeef5;
    color: #606266;
  }

  .col-name {
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-weight: 500;
    color: #303133;
  }

  .col-desc {
    max-width: 300px;
    white-space: normal;
  }

  /* relations */
  .tool-relations {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .tool-relation-item {
    display: flex;
    align-items: center;
    gap: 6px;
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
    padding: 6px 12px;
    font-size: 13px;
  }

  .tool-relation-arrow {
    color: #909399;
    font-weight: 600;
  }

  .tool-relation-cond {
    color: #909399;
    font-size: 12px;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }

  /* sql block */
  .tool-sql-block {
    background: #f6f8fa;
    border: 1px solid #e1e4e8;
    border-radius: 6px;
    padding: 12px 16px;
    overflow-x: auto;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 13px;
    line-height: 1.5;
    color: #24292e;
  }

  /* sql_guard decision */
  .tool-decision-bar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 0;
  }

  .tool-aligned {
    color: #606266;
    font-size: 13px;
    display: flex;
    align-items: center;
    gap: 8px;
  }

  /* problems */
  .tool-problems {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .tool-problem-item {
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
    padding: 12px;
  }

  .tool-problem-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }

  .tool-problem-code {
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 12px;
    color: #909399;
  }

  .tool-problem-title {
    font-weight: 600;
    color: #303133;
  }

  .tool-problem-message {
    color: #606266;
    font-size: 13px;
    margin-bottom: 4px;
  }

  .tool-problem-why {
    color: #e6a23c;
    font-size: 13px;
    padding: 8px;
    background: #fdf6ec;
    border-radius: 4px;
    margin-bottom: 4px;
  }

  .tool-problem-detail {
    font-size: 13px;
    color: #606266;
    display: flex;
    flex-direction: column;
    gap: 4px;
    background: #f5f7fa;
    padding: 8px;
    border-radius: 4px;
    margin-bottom: 4px;
  }

  .tool-problem-repair {
    color: #67c23a;
    font-size: 13px;
    padding: 8px;
    background: #f0f9eb;
    border-radius: 4px;
    border-left: 3px solid #67c23a;
  }

  /* fix suggestions */
  .tool-fix-list {
    margin: 0;
    padding-left: 20px;
    color: #606266;
    font-size: 13px;
  }

  .tool-fix-list li {
    margin-bottom: 4px;
  }

  /* rule checks */
  .tool-rule-checks {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .tool-rule-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 4px;
    font-size: 13px;
  }

  .tool-rule-detail {
    color: #303133;
    flex: 1;
  }

  .tool-rule-evidence {
    color: #909399;
    font-size: 12px;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }

  /* column profiles */
  .tool-profile-item {
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
    padding: 12px;
    margin-bottom: 8px;
  }

  .tool-profile-col-name {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    margin: 0 0 8px 0;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }

  .tool-profile-total {
    font-weight: 400;
    color: #909399;
    font-size: 12px;
  }

  .tool-profile-stats {
    display: flex;
    gap: 16px;
    font-size: 13px;
    color: #606266;
    margin-bottom: 6px;
    flex-wrap: wrap;
  }

  .tool-profile-top {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    margin-bottom: 4px;
  }

  .tool-profile-label {
    font-size: 13px;
    font-weight: 500;
    color: #606266;
  }

  .tool-profile-hints {
    font-size: 13px;
    color: #909399;
  }

  .tool-profile-hint {
    color: #e6a23c;
  }

  /* hits (semantic_model, knowledge) */
  .tool-hits {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .tool-hit-item {
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
    padding: 10px 12px;
  }

  .tool-hit-header {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .tool-hit-table {
    font-weight: 600;
    color: #303133;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }

  .tool-hit-col {
    color: #409eff;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 13px;
  }

  .tool-hit-title {
    font-weight: 600;
    color: #303133;
  }

  .tool-hit-desc {
    color: #606266;
    font-size: 13px;
    margin-top: 4px;
  }

  .tool-hit-snippet {
    color: #909399;
    font-size: 12px;
    margin-top: 4px;
    font-style: italic;
  }

  .tool-hit-source {
    color: #c0c4cc;
    font-size: 12px;
    margin-top: 2px;
  }

  .tool-hit-score {
    color: #67c23a;
    font-size: 12px;
    margin-left: auto;
  }

  .tool-hit-meta {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
    flex-wrap: wrap;
  }

  /* metric */
  .tool-metric-status {
    padding: 4px 0;
  }

  .tool-metric-basic {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .tool-metric-row {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    font-size: 13px;
  }

  .tool-metric-label {
    color: #909399;
    min-width: 48px;
    font-weight: 500;
    flex-shrink: 0;
  }

  .tool-metric-row code {
    background: #f0f2f5;
    padding: 1px 6px;
    border-radius: 3px;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 12px;
    color: #303133;
  }

  .tool-metric-opid {
    color: #909399;
    font-size: 12px;
  }

  /* skill time */
  .tool-time-display {
    text-align: center;
    padding: 16px;
    background: #fff;
    border: 1px solid #e4e7ed;
    border-radius: 6px;
  }

  .tool-time-main {
    font-size: 20px;
    font-weight: 600;
    color: #303133;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }

  .tool-time-iso,
  .tool-time-tz {
    font-size: 13px;
    color: #909399;
    margin-top: 4px;
  }

  /* raw json fallback */
  .tool-raw-json {
    background: #f6f8fa;
    border: 1px solid #e1e4e8;
    border-radius: 6px;
    padding: 12px 16px;
    overflow-x: auto;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 13px;
    line-height: 1.5;
    color: #24292e;
    white-space: pre-wrap;
    word-wrap: break-word;
  }

  .tool-parse-error {
    color: #f56c6c;
  }

  .tool-parse-error p {
    margin: 0 0 8px 0;
  }

  .tool-waiting {
    display: flex;
    align-items: center;
    gap: 8px;
    color: #909399;
    padding: 12px 0;
  }

  .tool-spinner {
    display: inline-block;
    width: 14px;
    height: 14px;
    border: 2px solid #dcdfe6;
    border-top-color: #409eff;
    border-radius: 50%;
    animation: tool-spin 0.8s linear infinite;
  }

  @keyframes tool-spin {
    to {
      transform: rotate(360deg);
    }
  }

  .tool-raw-text {
    background: #fef0f0;
    border: 1px solid #fbc4c4;
    border-radius: 4px;
    padding: 8px 12px;
    font-size: 12px;
    white-space: pre-wrap;
    word-wrap: break-word;
    overflow-x: auto;
  }
</style>
