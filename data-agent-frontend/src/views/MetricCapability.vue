<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
-->
<template>
  <BaseLayout>
    <div class="page-wrapper">
      <div class="content-header">
        <div>
          <h1>指标目录管理</h1>
          <p>维护指标业务语义、上下架状态，并按真实问数流程验证检索效果</p>
        </div>
        <div class="header-actions">
          <el-button :icon="RefreshRight" :loading="loading" @click="handleReload">
            刷新页面
          </el-button>
          <el-button type="primary" :icon="Refresh" :loading="refreshing" @click="handleRefresh">
            {{ refreshing ? '同步中...' : '同步指标目录' }}
          </el-button>
        </div>
      </div>

      <el-alert v-if="statusError" class="error-banner" type="error" :closable="false" show-icon>
        {{ statusError }}
      </el-alert>
      <el-alert
        v-if="statusData.lastRefreshError"
        class="error-banner"
        type="warning"
        :closable="false"
        show-icon
      >
        最近一次同步失败：{{ statusData.lastRefreshError }}
      </el-alert>

      <el-row v-if="!loading" class="status-section" :gutter="16">
        <el-col :span="6">
          <el-card shadow="hover">
            <StatusCard label="目录状态" :value="statusData.ready ? '就绪' : '未就绪'" />
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover">
            <StatusCard label="指标总数" :value="statusData.metricCount" />
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover">
            <StatusCard
              label="上架 / 下架"
              :value="`${statusData.onlineCount} / ${statusData.offlineCount}`"
            />
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="hover">
            <StatusCard label="本地修正" :value="statusData.overrideCount" />
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="!loading" class="search-card">
        <template #header>
          <div class="card-title">
            <div>
              <strong>按问数流程检索</strong>
              <div class="card-subtitle">与指标问数共用同一个后端检索方法、阈值和上下架过滤</div>
            </div>
          </div>
        </template>
        <div class="search-form">
          <el-select
            v-model="searchAgentId"
            clearable
            placeholder="选择 Agent（用于本地业务知识）"
            class="agent-select"
          >
            <el-option
              v-for="agent in agents"
              :key="agent.id"
              :label="agent.name"
              :value="agent.id"
            />
          </el-select>
          <el-input
            v-model="searchQuery"
            clearable
            placeholder="例如：看看本月实收趋势"
            @keyup.enter="handleSearch"
          />
          <el-button type="primary" :loading="searching" @click="handleSearch">检索指标</el-button>
        </div>

        <div v-if="searchResult" class="search-result">
          <el-alert :type="decisionAlertType" :closable="false" show-icon>
            {{ decisionLabel }}：{{ searchResult.summary }}
            <template v-if="searchResult.businessKnowledgeTerms.length">
              ，使用了本地术语 {{ searchResult.businessKnowledgeTerms.join('、') }}
            </template>
          </el-alert>
          <el-table
            v-if="searchResult.candidates.length"
            :data="searchResult.candidates"
            size="small"
            border
          >
            <el-table-column prop="rank" label="#" width="50" />
            <el-table-column prop="metricCode" label="指标编码" min-width="150" />
            <el-table-column prop="metricName" label="指标名称" min-width="140" />
            <el-table-column label="融合分数" width="100">
              <template #default="scope">{{ formatScore(scope.row.fusedScore) }}</template>
            </el-table-column>
            <el-table-column label="命中字段" min-width="150">
              <template #default="scope">
                <el-tag v-for="field in scope.row.matchedFields" :key="field" size="small">
                  {{ field }}
                </el-tag>
                <span v-if="!scope.row.matchedFields.length">语义召回</span>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="判断依据" min-width="180" />
          </el-table>
        </div>
      </el-card>

      <el-card v-if="!loading" class="catalog-card">
        <template #header>
          <div class="card-title">
            <div>
              <strong>有效指标目录</strong>
              <div class="card-subtitle">同步第三方目录不会覆盖本地修正和上下架状态</div>
            </div>
          </div>
        </template>
        <el-empty v-if="metrics.length === 0" description="尚未同步到有效指标" />
        <el-table v-else :data="metrics" stripe row-key="metricKey">
          <el-table-column type="expand">
            <template #default="scope">
              <div class="expand-content">
                <section>
                  <h4>有效业务描述</h4>
                  <p><strong>metricKey：</strong>{{ scope.row.metricKey }}</p>
                  <p>{{ scope.row.description || '-' }}</p>
                </section>
                <section>
                  <h4>API 绑定（只读）</h4>
                  <p>
                    {{ scope.row.contract.httpMethod }} {{ scope.row.contract.path }} ·
                    {{ scope.row.contract.operationId }}
                  </p>
                  <el-table
                    v-if="scope.row.contract.requestParameters.length"
                    :data="scope.row.contract.requestParameters"
                    size="small"
                    border
                  >
                    <el-table-column prop="name" label="参数" width="150" />
                    <el-table-column prop="location" label="位置" width="90" />
                    <el-table-column prop="type" label="类型" width="100" />
                    <el-table-column prop="description" label="说明" />
                  </el-table>
                </section>
                <section>
                  <h4>响应契约（只读）</h4>
                  <p>{{ scope.row.contract.response.description || '未提供响应说明' }}</p>
                  <p>
                    <strong>内容类型：</strong>{{ scope.row.contract.response.contentType || '-' }}
                    <template v-if="scope.row.contract.response.successCriteria">
                      · <strong>成功条件：</strong>
                      {{ scope.row.contract.response.successCriteria.jsonPath }}
                      {{ scope.row.contract.response.successCriteria.operator }}
                      {{ formatJson(scope.row.contract.response.successCriteria.expectedValue) }}
                    </template>
                  </p>
                  <p>
                    <strong>结果路径：</strong>{{ scope.row.contract.response.resultPath || '自动识别' }}
                    · <strong>消息路径：</strong
                    >{{ scope.row.contract.response.messagePath || '-' }}
                  </p>
                  <details class="contract-json">
                    <summary>查看请求与响应 Schema</summary>
                    <pre>{{
                      formatJson({
                        requestSchema: scope.row.contract.requestSchema,
                        responseSchema: scope.row.contract.response.schema,
                        responseExamples: scope.row.contract.response.examples,
                      })
                    }}</pre>
                  </details>
                </section>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="metricCode" label="指标编码" min-width="155" />
          <el-table-column prop="metricName" label="指标名称" min-width="150" />
          <el-table-column label="别名" min-width="190">
            <template #default="scope">
              <el-tag
                v-for="alias in scope.row.aliases"
                :key="alias"
                size="small"
                class="alias-tag"
              >
                {{ alias }}
              </el-tag>
              <span v-if="!scope.row.aliases.length">-</span>
            </template>
          </el-table-column>
          <el-table-column label="配置" width="110">
            <template #default="scope">
              <el-tag v-if="scope.row.hasLocalOverride" type="warning">本地修正</el-tag>
              <el-tag v-else type="info">原始定义</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="索引" width="100">
            <template #default="scope">
              <el-tooltip :content="scope.row.indexError || ''" :disabled="!scope.row.indexError">
                <el-tag :type="indexTagType(scope.row.indexStatus)">
                  {{ indexLabel(scope.row.indexStatus) }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="服务状态" width="100">
            <template #default="scope">
              <el-tag :type="scope.row.serviceStatus === 'ONLINE' ? 'success' : 'info'">
                {{ scope.row.serviceStatus === 'ONLINE' ? '已上架' : '已下架' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="scope">
              <el-button link type="primary" @click="openEdit(scope.row)">修正语义</el-button>
              <el-button
                link
                :type="scope.row.serviceStatus === 'ONLINE' ? 'danger' : 'success'"
                @click="toggleServiceStatus(scope.row)"
              >
                {{ scope.row.serviceStatus === 'ONLINE' ? '下架' : '上架' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="loading"><el-skeleton :rows="8" animated /></el-card>

      <el-dialog v-model="editVisible" title="本地指标语义修正" width="720px">
        <el-alert type="info" :closable="false" class="edit-hint">
          本地修正只影响指标检索，不会改变 API 路径、参数或响应契约。
        </el-alert>
        <el-form label-position="top">
          <el-form-item label="目录原始名称">
            <el-input :model-value="editingMetric?.sourceMetricName" disabled />
          </el-form-item>
          <el-form-item label="本地指标名称">
            <el-input v-model="editForm.localMetricName" placeholder="留空则使用原始名称" />
          </el-form-item>
          <el-form-item label="目录原始描述">
            <el-input
              :model-value="editingMetric?.sourceDescription"
              type="textarea"
              :rows="3"
              disabled
            />
          </el-form-item>
          <el-form-item label="本地指标描述">
            <el-input
              v-model="editForm.localDescription"
              type="textarea"
              :rows="4"
              placeholder="留空则使用原始描述"
            />
          </el-form-item>
          <el-form-item label="本地补充别名">
            <el-input v-model="editForm.localAliases" placeholder="多个别名使用逗号分隔" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button v-if="editingMetric?.hasLocalOverride" @click="clearOverride">
            恢复原始定义
          </el-button>
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveOverride">
            保存并重建索引
          </el-button>
        </template>
      </el-dialog>
    </div>
  </BaseLayout>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref } from 'vue';
  import axios from 'axios';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Refresh, RefreshRight } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import AgentService, { type Agent } from '@/services/agent';
  import metricCapabilityService, {
    type MetricCapabilityStatus,
    type MetricCatalogView,
    type MetricSearchResult,
  } from '@/services/metricCapability';

  const StatusCard = (props: { label: string; value: string | number }) =>
    h('div', { class: 'status-card' }, [
      h('span', { class: 'status-label' }, props.label),
      h('span', { class: 'status-value' }, String(props.value)),
    ]);

  const emptyStatus = (): MetricCapabilityStatus => ({
    ready: false,
    definitionCount: 0,
    metricCount: 0,
    onlineCount: 0,
    offlineCount: 0,
    overrideCount: 0,
    generation: '',
    state: 'not_ready',
    lastRefreshSuccessTime: 0,
    lastRefreshFailureTime: 0,
    lastRefreshError: '',
    circuitBreakerState: '',
  });

  const loading = ref(true);
  const refreshing = ref(false);
  const searching = ref(false);
  const saving = ref(false);
  const statusError = ref('');
  const statusData = ref<MetricCapabilityStatus>(emptyStatus());
  const metrics = ref<MetricCatalogView[]>([]);
  const agents = ref<Agent[]>([]);
  const searchAgentId = ref<number>();
  const searchQuery = ref('');
  const searchResult = ref<MetricSearchResult>();
  const editVisible = ref(false);
  const editingMetric = ref<MetricCatalogView>();
  const editForm = reactive({ localMetricName: '', localDescription: '', localAliases: '' });

  const decisionLabel = computed(() => {
    const labels = { MATCH: '已命中', AMBIGUOUS: '需要澄清', NO_MATCH: '未命中' };
    return searchResult.value ? labels[searchResult.value.decision] : '';
  });
  const decisionAlertType = computed(() => {
    if (searchResult.value?.decision === 'MATCH') return 'success';
    if (searchResult.value?.decision === 'AMBIGUOUS') return 'warning';
    return 'info';
  });

  const errorMessage = (error: unknown): string => {
    if (axios.isAxiosError(error)) {
      return error.response?.data?.message || error.message;
    }
    return error instanceof Error ? error.message : '未知错误';
  };

  const loadStatus = async () => {
    statusData.value = await metricCapabilityService.status();
    statusError.value = '';
  };
  const loadMetrics = async () => {
    metrics.value = await metricCapabilityService.metrics();
  };
  const handleReload = async () => {
    loading.value = true;
    try {
      await Promise.all([loadStatus(), loadMetrics()]);
    } catch (error) {
      statusError.value = errorMessage(error);
    } finally {
      loading.value = false;
    }
  };

  const handleRefresh = async () => {
    refreshing.value = true;
    const previousSuccessTime = statusData.value.lastRefreshSuccessTime;
    try {
      const result = await metricCapabilityService.refresh();
      ElMessage.success(result.message);
      for (let index = 0; index < 15; index++) {
        await new Promise(resolve => window.setTimeout(resolve, 2000));
        await loadStatus();
        if (statusData.value.lastRefreshSuccessTime > previousSuccessTime) {
          await loadMetrics();
          ElMessage.success('OpenAPI 同步及指标索引切换完成');
          return;
        }
        if (statusData.value.lastRefreshFailureTime > previousSuccessTime) {
          throw new Error(statusData.value.lastRefreshError || '指标目录同步失败');
        }
      }
      ElMessage.info('同步仍在进行，可稍后刷新页面查看');
    } catch (error) {
      ElMessage.error(`同步失败：${errorMessage(error)}`);
    } finally {
      refreshing.value = false;
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.value.trim()) {
      ElMessage.warning('请输入需要验证的用户问题');
      return;
    }
    searching.value = true;
    try {
      searchResult.value = await metricCapabilityService.search(
        searchQuery.value.trim(),
        searchAgentId.value,
      );
    } catch (error) {
      ElMessage.error(`检索失败：${errorMessage(error)}`);
    } finally {
      searching.value = false;
    }
  };

  const openEdit = (metric: MetricCatalogView) => {
    editingMetric.value = metric;
    editForm.localMetricName = metric.localMetricName || '';
    editForm.localDescription = metric.localDescription || '';
    editForm.localAliases = metric.localAliases.join('，');
    editVisible.value = true;
  };

  const saveOverride = async () => {
    if (!editingMetric.value) return;
    saving.value = true;
    try {
      await metricCapabilityService.saveOverride(editingMetric.value.metricKey, {
        localMetricName: editForm.localMetricName.trim() || undefined,
        localDescription: editForm.localDescription.trim() || undefined,
        localAliases: editForm.localAliases
          .split(/[,，]/)
          .map(item => item.trim())
          .filter(Boolean),
      });
      editVisible.value = false;
      await Promise.all([loadStatus(), loadMetrics()]);
      ElMessage.success('本地语义已保存并对指标检索生效');
    } catch (error) {
      ElMessage.error(`保存失败：${errorMessage(error)}`);
    } finally {
      saving.value = false;
    }
  };

  const clearOverride = async () => {
    if (!editingMetric.value) return;
    await ElMessageBox.confirm('确认清除本地修正并恢复目录原始定义？', '恢复原始定义', {
      type: 'warning',
    });
    saving.value = true;
    try {
      await metricCapabilityService.clearOverride(editingMetric.value.metricKey);
      editVisible.value = false;
      await Promise.all([loadStatus(), loadMetrics()]);
      ElMessage.success('已恢复原始指标定义');
    } catch (error) {
      ElMessage.error(`恢复失败：${errorMessage(error)}`);
    } finally {
      saving.value = false;
    }
  };

  const toggleServiceStatus = async (metric: MetricCatalogView) => {
    const nextStatus = metric.serviceStatus === 'ONLINE' ? 'OFFLINE' : 'ONLINE';
    const action = nextStatus === 'ONLINE' ? '上架' : '下架';
    await ElMessageBox.confirm(
      nextStatus === 'OFFLINE'
        ? '下架后该指标不能被问数检索、描述或执行，但不会删除配置。'
        : '上架前会先重建并验证指标索引，成功后才对问数生效。',
      `确认${action}“${metric.metricName}”？`,
      { type: 'warning' },
    );
    try {
      await metricCapabilityService.updateServiceStatus(metric.metricKey, nextStatus);
      await Promise.all([loadStatus(), loadMetrics()]);
      ElMessage.success(`指标已${action}`);
    } catch (error) {
      ElMessage.error(`${action}失败：${errorMessage(error)}`);
    }
  };

  const formatScore = (score?: number) => (score == null ? '-' : score.toFixed(4));
  const formatJson = (value: unknown) => JSON.stringify(value ?? null, null, 2);
  const indexLabel = (status: MetricCatalogView['indexStatus']) =>
    ({ PENDING: '处理中', COMPLETED: '已生效', FAILED: '失败' })[status];
  const indexTagType = (status: MetricCatalogView['indexStatus']) =>
    ({ PENDING: 'warning', COMPLETED: 'success', FAILED: 'danger' })[status];

  onMounted(async () => {
    await handleReload();
    try {
      agents.value = await AgentService.list();
    } catch {
      agents.value = [];
    }
  });
</script>

<style scoped>
  .page-wrapper {
    max-width: 1500px;
    margin: 0 auto;
    padding: 2rem;
  }
  .content-header {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    margin-bottom: 1.5rem;
  }
  .content-header h1 {
    margin: 0 0 0.25rem;
    color: var(--el-text-color-primary);
    font-size: 1.5rem;
  }
  .content-header p,
  .card-subtitle {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 0.875rem;
  }
  .header-actions,
  .search-form,
  .card-title {
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }
  .error-banner,
  .status-section,
  .search-card,
  .catalog-card {
    margin-bottom: 1rem;
  }
  .status-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .status-label {
    color: var(--el-text-color-secondary);
    font-size: 0.875rem;
  }
  .status-value {
    color: var(--el-text-color-primary);
    font-size: 1.35rem;
    font-weight: 700;
  }
  .agent-select {
    width: 280px;
    flex: 0 0 auto;
  }
  .search-result {
    display: grid;
    gap: 1rem;
    margin-top: 1rem;
  }
  .alias-tag {
    margin: 2px 4px 2px 0;
  }
  .expand-content {
    display: grid;
    gap: 1rem;
    padding: 1rem 2rem;
    background: var(--el-fill-color-lighter);
  }
  .expand-content h4 {
    margin: 0 0 0.5rem;
    color: var(--el-text-color-regular);
  }
  .expand-content p {
    margin: 0 0 0.75rem;
    color: var(--el-text-color-secondary);
  }
  .edit-hint {
    margin-bottom: 1rem;
  }
  .contract-json {
    color: var(--el-text-color-secondary);
  }
  .contract-json pre {
    max-height: 360px;
    margin: 0.75rem 0 0;
    padding: 0.75rem;
    overflow: auto;
    border: 1px solid var(--el-border-color);
    border-radius: 6px;
    background: var(--el-bg-color);
    color: var(--el-text-color-primary);
    white-space: pre-wrap;
  }
  @media (max-width: 900px) {
    .content-header,
    .search-form {
      align-items: stretch;
      flex-direction: column;
    }
    .agent-select {
      width: 100%;
    }
  }
</style>
