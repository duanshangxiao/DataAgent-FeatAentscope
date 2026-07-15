<!--
 * Copyright 2024-2026 the original author or authors.
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
<template>
  <BaseLayout>
    <div class="page-wrapper">
      <div class="content-header">
        <h1>指标目录管理</h1>
        <p>查看已同步的指标接口，监控目录状态，手动触发刷新</p>
      </div>

      <div v-if="statusError" class="error-banner">
        <el-alert type="error" :closable="false" show-icon>
          <template #title>无法获取指标目录状态</template>
          {{ statusError }}
        </el-alert>
      </div>

      <div v-if="refreshError" class="error-banner">
        <el-alert
          type="warning"
          :closable="true"
          show-icon
          @close="refreshError = ''"
        >
          <template #title>最近一次刷新失败</template>
          {{ refreshError }}
        </el-alert>
      </div>

      <div class="status-section" v-if="!loading">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-card shadow="hover">
              <div class="status-card">
                <span class="status-label">目录状态</span>
                <el-tag
                  :type="statusData.ready ? 'success' : 'danger'"
                  size="large"
                  effect="dark"
                >
                  {{ statusData.ready ? '就绪' : '未就绪' }}
                </el-tag>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover">
              <div class="status-card">
                <span class="status-label">指标接口数量</span>
                <span class="status-value">{{ statusData.definitionCount }}</span>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover">
              <div class="status-card">
                <span class="status-label">熔断器状态</span>
                <el-tag
                  :type="circuitBreakerTagType"
                  size="large"
                  effect="dark"
                >
                  {{ statusData.circuitBreakerState || '未知' }}
                </el-tag>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <div class="action-section" v-if="!loading">
        <el-card>
          <div class="action-content">
            <el-button type="primary" :icon="Refresh" @click="handleRefresh" :loading="refreshing">
              {{ refreshing ? '刷新中...' : '刷新目录' }}
            </el-button>
            <el-button :icon="RefreshRight" @click="handleReload" :loading="loading">刷新页面</el-button>
            <span class="action-hint" v-if="statusData.lastRefreshSuccessSecondsAgo != null">
              上次刷新于 {{ formatSecondsAgo(statusData.lastRefreshSuccessSecondsAgo) }}
            </span>
            <span class="action-hint" v-else>
              尚未成功刷新过
            </span>
          </div>
        </el-card>
      </div>

      <div class="data-section" v-if="!loading">
        <el-card>
          <div v-if="definitions.length === 0" class="empty-state">
            <el-empty v-if="statusData.ready" description="接口文档解析成功，但未识别到指标接口" />
            <el-empty v-else description="指标目录尚未同步，请检查指标系统配置后点击刷新" />
          </div>

          <el-table
            v-else
            :data="definitions"
            stripe
            style="width: 100%"
            row-key="operationId"
          >
            <el-table-column type="expand">
              <template #default="scope">
                <div class="expand-content">
                  <div class="expand-section" v-if="scope.row.description">
                    <h4>详细说明</h4>
                    <p>{{ scope.row.description }}</p>
                  </div>
                  <div class="expand-section" v-if="scope.row.aliases && scope.row.aliases.length">
                    <h4>别名</h4>
                    <div class="tag-list">
                      <el-tag
                        v-for="alias in scope.row.aliases"
                        :key="alias"
                        size="small"
                        style="margin-right: 8px"
                      >{{ alias }}</el-tag>
                    </div>
                  </div>
                  <div class="expand-section" v-if="scope.row.requestParameters && scope.row.requestParameters.length">
                    <h4>请求参数</h4>
                    <el-table :data="scope.row.requestParameters" size="small" border>
                      <el-table-column prop="name" label="参数名" width="140" />
                      <el-table-column label="必填" width="70">
                        <template #default="{ row }">
                          <el-tag :type="row.required ? 'danger' : 'info'" size="small" effect="plain">
                            {{ row.required ? '必填' : '可选' }}
                          </el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column prop="location" label="位置" width="80" />
                      <el-table-column prop="type" label="类型" width="80" />
                      <el-table-column prop="description" label="说明" />
                      <el-table-column label="枚举值" width="160">
                        <template #default="{ row }">
                          <template v-if="row.enumValues && row.enumValues.length">
                            <el-tag
                              v-for="val in row.enumValues"
                              :key="val"
                              size="small"
                              style="margin-right: 4px"
                            >{{ val }}</el-tag>
                          </template>
                          <span v-else style="color: #999">-</span>
                        </template>
                      </el-table-column>
                    </el-table>
                  </div>
                  <div class="expand-section" v-if="scope.row.supportedGranularities && scope.row.supportedGranularities.length">
                    <h4>支持的粒度</h4>
                    <el-tag
                      v-for="g in scope.row.supportedGranularities"
                      :key="g"
                      size="small"
                      style="margin-right: 8px"
                    >{{ g }}</el-tag>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="metricCode" label="指标编码" width="180" />
            <el-table-column prop="metricName" label="指标名称" width="160">
              <template #default="scope">
                <el-tag type="primary" effect="plain" size="small">{{ scope.row.metricName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="请求方式" width="90">
              <template #default="scope">
                <el-tag
                  :type="methodTagType(scope.row.httpMethod)"
                  size="small"
                  effect="dark"
                >{{ scope.row.httpMethod }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="path" label="接口路径" min-width="240" show-overflow-tooltip />
            <el-table-column prop="summary" label="接口描述" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-card>
      </div>

      <div v-if="loading" class="loading-section">
        <el-card>
          <el-skeleton :rows="6" animated />
        </el-card>
      </div>
    </div>
  </BaseLayout>
</template>

<script>
import { defineComponent, ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import BaseLayout from '@/layouts/BaseLayout.vue';
import metricCapabilityService from '@/services/metricCapability';

export default defineComponent({
  name: 'MetricCapability',
  components: { BaseLayout },
  setup() {
    const loading = ref(true);
    const refreshing = ref(false);
    const statusError = ref('');
    const refreshError = ref('');
    const statusData = ref({
      ready: false,
      definitionCount: 0,
      state: 'not_ready',
      lastRefreshSuccessTime: 0,
      lastRefreshSuccessSecondsAgo: null,
      lastRefreshFailureTime: 0,
      lastRefreshError: '',
      circuitBreakerState: '',
    });
    const definitions = ref([]);

    const loadStatus = async () => {
      try {
        const data = await metricCapabilityService.status();
        statusData.value = data;
        statusError.value = '';
        if (data.lastRefreshError) {
          refreshError.value = data.lastRefreshError;
        }
      } catch (err) {
        statusError.value = err.message || '网络请求失败';
      }
    };

    const loadDefinitions = async () => {
      try {
        const data = await metricCapabilityService.definitions();
        definitions.value = data || [];
      } catch (err) {
        console.error('Failed to load definitions:', err);
      }
    };

    const handleReload = async () => {
      loading.value = true;
      await loadStatus();
      await loadDefinitions();
      loading.value = false;
    };

    const handleRefresh = async () => {
      refreshing.value = true;
      try {
        const result = await metricCapabilityService.refresh();
        ElMessage.success(result.message || '刷新已触发');
        let pollCount = 0;
        const maxPolls = 15;
        const poll = async () => {
          if (pollCount >= maxPolls) {
            ElMessage.info('刷新可能仍在进行中，已停止等待，可手动刷新页面查看');
            refreshing.value = false;
            await handleReload();
            return;
          }
          pollCount++;
          await new Promise((resolve) => setTimeout(resolve, 3000));
          try {
            await loadStatus();
            if (statusData.value.ready) {
              await loadDefinitions();
              refreshing.value = false;
              ElMessage.success('目录刷新完成');
              return;
            }
          } catch (e) {
            // ignore poll errors
          }
          await poll();
        };
        await poll();
      } catch (err) {
        ElMessage.error('刷新失败: ' + (err.response?.data?.message || err.message));
        refreshing.value = false;
      }
    };

    const formatSecondsAgo = (seconds) => {
      if (seconds == null) return '未知';
      if (seconds < 60) return `${seconds} 秒前`;
      if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
      return `${Math.floor(seconds / 3600)} 小时前`;
    };

    const methodTagType = (method) => {
      const map = { GET: 'success', POST: 'primary', PUT: 'warning', DELETE: 'danger', PATCH: 'info' };
      return map[method?.toUpperCase()] || 'info';
    };

    const circuitBreakerTagType = (() => {
      const map = { CLOSED: 'success', OPEN: 'danger', HALF_OPEN: 'warning' };
      return map[statusData.value.circuitBreakerState] || 'info';
    })();

    onMounted(() => {
      handleReload();
    });

    return {
      loading,
      refreshing,
      statusError,
      refreshError,
      statusData,
      definitions,
      handleReload,
      handleRefresh,
      formatSecondsAgo,
      methodTagType,
      circuitBreakerTagType,
      Refresh,
      RefreshRight,
    };
  },
});
</script>

<style scoped>
.page-wrapper {
  max-width: 1400px;
  margin: 0 auto;
  padding: 2rem;
}

.content-header {
  margin-bottom: 1.5rem;
}

.content-header h1 {
  font-size: 1.5rem;
  font-weight: 600;
  color: #1e293b;
  margin: 0 0 0.25rem 0;
}

.content-header p {
  color: #94a3b8;
  margin: 0;
  font-size: 0.875rem;
}

.error-banner {
  margin-bottom: 1rem;
}

.status-section {
  margin-bottom: 1rem;
}

.status-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.status-label {
  font-size: 0.875rem;
  color: #64748b;
}

.status-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: #1e293b;
}

.action-section {
  margin-bottom: 1rem;
}

.action-content {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.action-hint {
  margin-left: auto;
  font-size: 0.875rem;
  color: #94a3b8;
}

.data-section {
  margin-bottom: 2rem;
}

.empty-state {
  padding: 3rem 0;
}

.loading-section {
  margin-bottom: 2rem;
}

.expand-content {
  padding: 1rem 2rem;
  background: #f8fafc;
}

.expand-section {
  margin-bottom: 1rem;
}

.expand-section:last-child {
  margin-bottom: 0;
}

.expand-section h4 {
  font-size: 0.875rem;
  font-weight: 600;
  color: #475569;
  margin: 0 0 0.5rem 0;
}

.expand-section p {
  color: #64748b;
  font-size: 0.875rem;
  margin: 0;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
}
</style>
