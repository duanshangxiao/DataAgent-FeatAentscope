# DataAgent 指标能力未启用问题修复提示词

你现在是负责修复 DataAgent 指标 capability 问题的实现智能体。请基于下面的上下文、现象、定位结论与约束，直接分析、修改代码、补充必要日志与验证手段，最终让指标 capability 能正常启用，并能稳定识别像“活跃用户数”这样的标准指标问题。

---

## 1. 目标

请修复以下问题：

1. DataAgent 已配置指标 capability，也能成功访问 Swagger/OpenAPI 文档，但运行时仍然提示 `Metric capability disabled because catalog is not ready`。
2. 因为 catalog 没有 ready，`CapabilityRegistry` 中没有可用 capability provider，最终所有请求都退化为 `DB_ONLY`。
3. 修复后需要保证：
   - 可以从当前指标系统 Swagger 中成功解析出有效的指标定义。
   - `metricOpenApiSyncService.isReady()` 能在正常场景下返回 `true`。
   - Agent 在绑定 `builtin-metric-system` 后，运行时能装配 `metric.catalog.search`、`metric.catalog.describe`、`metric.query.execute`。
   - 对“活跃用户数”等问法，后续具备进入指标链路的基础。

---

## 2. 已知现象

当前运行日志如下，说明 capability 在 `enabledForAgent()` 阶段就被提前判定为不可用：

```text
Metric capability disabled because catalog is not ready. agentId=2, swaggerUrl=http://127.0.0.1:8081/v3/api-docs/tool
No capability providers enabled for agent. agentId=2
Capability routing resolved. agentId=2, ..., routeType=DB_ONLY, ...
Routed tool callbacks prepared. agentId=2, ..., metricToolCount=0, ...
```

这说明问题不在运行时路由后半段，而在 Swagger -> catalog -> enabledForAgent 这一段。

---

## 3. 已确认事实

### 3.1 Swagger 地址可访问

以下命令已验证成功：

```bash
curl --location 'http://127.0.0.1:8081/v3/api-docs/tool'
```

返回的是合法 OpenAPI 3.1 JSON，并非网络不可达或空文档。

### 3.2 Swagger 中既有无关 CRUD 接口，也有指标相关接口

文档中至少包含以下指标类接口：

1. `/test/metrics/mock/trend`
2. `/test/metrics/mock/ranking`
3. `/test/metrics/mock/overview`
4. `/test/metrics/mock/alerts`

其中响应 schema 中包含以下指标相关字段与示例：

1. `metricCode`
2. `metricName`
3. `active_user_count`
4. `活跃用户数`

### 3.3 当前 OpenAPI 的 response content 主要使用 `*/*`

例如：

```json
"responses": {
  "200": {
    "description": "OK",
    "content": {
      "*/*": {
        "schema": {
          "$ref": "#/components/schemas/ApiResponseMetricTrendResponse"
        }
      }
    }
  }
}
```

这份文档是标准 OpenAPI，但当前项目代码不一定兼容这种 media type 写法。

---

## 4. 已定位的高概率根因

请优先围绕以下根因核实与修复：

1. 当前 `MetricOpenApiParser` / `MetricOpenApiSyncService` 的解析逻辑过于收窄，只识别 `application/json`，没有兼容 `*/*` 或其他合法 content-type。
2. 因为响应 schema 没有被成功提取，`toDefinition()` 里构造 `MetricDefinition` 时被过滤掉，最终 `definitions.isEmpty()`。
3. `definitions.isEmpty()` 导致 `metricCatalogIndex.refresh(...)` 不会执行，catalog 永远 not ready。
4. 即使 catalog ready，当前“指标定义抽取”可能仍然不够强，因为真实指标信息更多埋在 response schema、description、example 中，而不仅仅是 `summary` 和 `operationId`。

---

## 5. 重点代码位置

请优先阅读并修改以下文件：

1. [MetricCapabilityProvider.java](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/metric/MetricCapabilityProvider.java)
2. [CapabilityRoutingService.java](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/CapabilityRoutingService.java)
3. [AiAgentRuntimeServiceImpl.java](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java)
4. [data-agent-metric-capability-implementation-plan.md](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/docs/duan/data-agent-metric-capability-implementation-plan.md)

重点关注下列方法：

1. `MetricOpenApiSyncService.refreshCatalog()`
2. `MetricOpenApiParser.parse(...)`
3. `MetricOpenApiParser.toDefinition(...)`
4. `MetricOpenApiParser.resolveRequestSchema(...)`
5. `MetricOpenApiParser.resolveResponseSchema(...)`
6. `MetricCapabilityProvider.enabledForAgent(...)`
7. `MetricCapabilityProvider.route(...)`

---

## 6. 你的任务

请按顺序完成以下工作：

1. 继续确认 `definitions.isEmpty()` 的真实原因，并通过日志或调试输出证明。
2. 修复 OpenAPI 解析逻辑，至少兼容：
   - `application/json`
   - `*/*`
   - 其他 content 下可用的第一个 schema
3. 不要因为文档里混有 CRUD 接口就让所有接口都失败；要确保指标相关接口至少能被提取成有效定义。
4. 视情况增强指标定义抽取逻辑：
   - 优先从 `x-metric-code`、`x-metric-name`、`x-aliases` 读取
   - 若文档没有这些扩展字段，可尝试从 response schema 中的 `metricCode` / `metricName` 示例、description 或 path/tag 中提取更合理的定义
5. 增加必要的诊断日志，帮助后续快速判断：
   - 拉取文档成功与否
   - 解析出的 definition 数量
   - 被过滤掉的接口原因
   - 前几个 definition 的 `metricCode` / `metricName`
6. 确保修复后不会影响原有数据库链路。
7. 如有必要，补充或更新针对 parser / catalog 的单元测试。

---

## 7. 修复要求

请遵循以下要求：

1. 优先做最小可行修复，不要重写整套 capability 框架。
2. 保持现有 capability 路由设计不变，重点修 catalog 构建与解析兼容性。
3. 不要只做“放宽 ready 条件”这种表面修复，必须让 catalog 里真的有可用定义。
4. 修复后要保留足够日志，便于继续定位“活跃用户数”路由是否命中。
5. 如果发现除了 `*/*` 之外还有其他根因，也请一并修复，但要在最终说明中区分主因与次因。

---

## 8. 建议的验证步骤

完成代码修改后，请至少验证以下内容：

1. 启动 DataAgent 后，不再出现 `catalog is not ready`。
2. 日志中出现类似：
   - `Metric catalog refreshed successfully. definitionCount=...`
   - 解析得到的部分 `metricCode` / `metricName`
3. Agent 绑定 `builtin-metric-system` 后，请求运行日志中能看到：
   - `metricToolCount > 0`
   - `metric.catalog.search` 被装配到工具集中
4. 对“活跃用户数趋势”或类似问题，路由不再因为 capability 未启用而直接走 `DB_ONLY`。
5. 如果暂时还不能稳定命中 `METRIC_ONLY`，至少要把问题推进到“目录已建好，但路由命中规则还需增强”这一层，而不是停留在 catalog not ready。

---

## 9. 期望输出

完成修复后，请输出：

1. 根因总结
2. 修改的文件列表
3. 关键修复点
4. 如何验证修复有效
5. 还剩下的风险或后续优化建议

如果你发现“活跃用户数”仍不能稳定命中指标系统，请继续说明是目录抽取质量问题、别名不足问题，还是路由打分问题，不要模糊带过。

---

## 10. 补充说明

请不要把“Swagger 文档合法”与“当前 parser 能正确解析”混为一谈。当前需要修复的是 DataAgent 对这份合法 OpenAPI 的兼容与提取能力。

当前最重要的目标不是优化回答内容，而是让指标 capability 真正启用，并让后续路由与工具链有机会工作。
