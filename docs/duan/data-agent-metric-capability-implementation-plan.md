# DataAgent 指标系统接入与通用外部能力扩展方案（面向实现智能体）

## 1. 文档目标

本方案要解决三个问题：

1. **指标问题路由**
   - 当用户问题属于“指标系统已提供的标准指标查询”时，优先调用指标系统 HTTP 接口。
   - 不允许这类问题继续走数据库 SQL 计算口径，避免与指标平台口径不一致。

2. **数据库问题保留原链路**
   - 当用户问题不属于指标系统能力范围时，仍然走当前 DataAgent 的数据库问数链路。

3. **沉淀通用扩展框架**
   - 当前接入的是“指标系统”。
   - 后续若接入其他外部系统，也要走同一套能力注册、路由、工具暴露、元数据同步、解释链路，不要为每个系统重写一套临时代码。

---

## 2. 前提假设

以下假设是本方案成立的前提：

1. 指标系统采用 **无鉴权** 方式，DataAgent 可直接访问。
2. 指标系统提供 **稳定的 Swagger/OpenAPI 文档地址**。
3. Swagger 文档内容完整，至少包含：
   - 接口路径、方法、operationId
   - summary、description
   - 请求参数定义、必填约束、枚举、示例
   - 响应结构定义、示例
4. 指标系统接口风格统一，适合抽象成统一的查询模型。
5. 当前产品要求是：
   - 指标问题必须优先走指标系统
   - 非指标问题继续走数据库问数
   - 后续其他外部能力要复用本次的技术框架

---

## 3. 非目标

本次方案不解决以下内容：

1. 不处理外部系统的复杂鉴权体系，如 OAuth2、AK/SK、动态 Token 刷新。
2. 不处理多指标系统并行接入时的冲突仲裁策略。
3. 不实现复杂 BI 编排平台，只做“路由 + 工具调用 + 结果归一化”。
4. 不重写当前 DataAgent 的数据库问数主链路。
5. 不要求第一阶段支持非常复杂的混合多跳计划，只需要能稳定处理单指标问题、普通数据库问题、简单混合问题。

---

## 4. 当前系统结构与接入点

本节只列与本次改造直接相关的现有代码落点。

### 4.1 请求入口与运行时主链路

- SSE 入口：[DataAgentController.streamSearch](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/DataAgentController.java#L51-L100)
- 异步执行入口：[AiAgentRuntimeServiceImpl.graphStreamProcess](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java#L128-L151)
- Agent 执行核心：[AiAgentRuntimeServiceImpl.executeAgent](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java#L216-L287)

### 4.2 工具装配链路

- 工具汇总工厂：[AgentScopeToolkitFactory](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeToolkitFactory.java#L47-L126)
- Agent 级工具目录：[AgentScopedToolCatalogService](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/AgentScopedToolCatalogService.java#L31-L62)
- 技能装配工厂：[AgentScopeSkillBoxFactory](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeSkillBoxFactory.java#L44-L76)
- 运行时扩展装配：[AgentRuntimeExtensionFactory](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentRuntimeExtensionFactory.java#L45-L61)

### 4.3 当前数据库问数能力

- 数据源探索工具提供器：[DatasourceExplorerToolProvider](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerToolProvider.java#L84-L100)
- 数据源探索服务：[DatasourceExplorerService](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerService.java#L115-L132)
- 工具路由规则提示词：[commonagent.md](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/resources/prompts/commonagent.md)

### 4.4 当前技能扩展机制

- Agent 绑定技能接口：[AgentSkillController](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/AgentSkillController.java#L51-L71)
- 本地/内置技能加载：[LocalSkillServiceImpl](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/skill/impl/LocalSkillServiceImpl.java#L178-L236)
- Skill 绑定工具接口：[SkillBoundToolProvider](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/skilltool/SkillBoundToolProvider.java#L21-L26)
- 内置技能工具示例：[BuiltinCurrentTimeSkillToolProvider](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/skilltool/BuiltinCurrentTimeSkillToolProvider.java#L34-L116)

### 4.5 当前解释链路

- 解释结果存储：[AnswerTraceExplainStore](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/observability/AnswerTraceExplainStore.java#L38-L172)
- 查询 explain 接口：[ChatController](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/ChatController.java#L144-L162)

---

## 5. 方案总览

本次改造采用以下总体思路：

1. 在当前运行时主链路中，新增一个 **能力路由层**。
2. 将“指标系统”封装为第一个标准化的 **外部能力 Capability**。
3. 利用 Swagger 文档生成本地 **指标目录索引**，支撑路由与工具调用。
4. 对 Agent 仅暴露稳定的高层工具，不直接暴露 Swagger 全量原始接口。
5. 复用当前 `ToolCallback`、`SkillBox`、`skillInstructions`、`AnswerTraceExplainStore` 等现有基础设施，避免推翻现有架构。

---

## 6. 总体架构

建议新增一层通用能力框架，核心组件如下：

1. `CapabilityProvider`
   - 外部能力提供者统一接口
   - 指标系统是第一个实现

2. `CapabilityRegistry`
   - 管理所有 `CapabilityProvider`
   - 按 Agent 和开关返回可用能力集

3. `CapabilityRoutingService`
   - 对用户问题进行能力路由
   - 输出 `METRIC_ONLY / DB_ONLY / MIXED / UNKNOWN`

4. `MetricCapabilityProvider`
   - 指标系统能力实现
   - 负责 Swagger 同步、指标目录生成、工具注册、HTTP 执行

5. `MetricCatalogIndex`
   - 内存指标索引
   - 支撑路由与 `metric.catalog.search`

6. `MetricToolProvider`
   - 对 Agent 暴露稳定工具：
     - `metric.catalog.search`
     - `metric.catalog.describe`
     - `metric.query.execute`

7. `MetricQueryExecutionService`
   - 将统一请求转换成指标系统 HTTP 调用
   - 将结果归一化成统一结构

8. `CapabilityExplainRecorder`
   - 将路由结果、指标调用摘要写入 explain 链路

---

## 7. 核心设计原则

实现时必须遵守以下原则：

1. **指标问题优先**
   - 一旦问题被识别为标准指标问题，不允许改走数据库 SQL 自行计算。

2. **工具稳定暴露**
   - 不对 Agent 暴露 Swagger 原始全量接口。
   - 只暴露少量稳定高层工具。

3. **结果统一归一化**
   - 指标系统结果与数据库查询结果都要落到统一输出结构，便于后续回答和 explain 展示。

4. **路由优先于工具选择**
   - 先路由，后决定暴露哪些工具。
   - 不要把“路由”完全交给 LLM 自由发挥。

5. **框架优先于单点实现**
   - 本次不是写“指标系统特例代码”。
   - 本次必须沉淀可复用的 `CapabilityProvider` 框架。

---

## 8. 路由模型

### 8.1 路由结果枚举

建议新增：

- `METRIC_ONLY`
- `DB_ONLY`
- `MIXED`
- `UNKNOWN`

### 8.2 判定含义

1. `METRIC_ONLY`
   - 用户问题明确属于指标平台已有标准指标
   - 例如：GMV、DAU、环比、同比、趋势、TopN 指标排行

2. `DB_ONLY`
   - 用户问题是数据库自由查询、结构探索、明细查看
   - 例如：看表、看字段、查明细、临时 SQL 分析

3. `MIXED`
   - 问题中同时包含：
     - 一个标准指标查询
     - 一个数据库补充查询或明细支撑查询

4. `UNKNOWN`
   - 无法稳定判断，应优先澄清，而不是盲目执行

### 8.3 路由判定策略

V1 采用“规则 + 指标目录召回”的组合，先不引入额外模型判定。

#### 第一层：规则匹配

重点词示例：

- 指标词：`指标`、`口径`、`GMV`、`DAU`、`留存`、`活跃`、`订单量`
- 分析词：`同比`、`环比`、`趋势`、`Top`、`排行`
- 时间词：`最近7天`、`本月`、`昨日`

#### 第二层：指标目录召回

使用 Swagger 解析出的 `MetricDefinition` 做召回：

- 匹配字段：
  - `metricName`
  - `aliases`
  - `description`
  - `tags`
  - `operationId`
- 召回结果输出：
  - TopN 候选指标
  - 匹配分数
  - 命中的关键词

#### 第三层：保守回退

- 命中指标能力且置信度高：`METRIC_ONLY`
- 完全未命中指标能力：`DB_ONLY`
- 同时出现明显指标词与明细/表结构诉求：`MIXED`
- 低置信度：`UNKNOWN`

---

## 9. 路由在现有运行时中的接入点

### 9.1 推荐插入位置

在 [AiAgentRuntimeServiceImpl.executeAgent](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java#L228-L248) 中插入：

顺序调整为：

1. 现有 clarify assessment
2. 若无需澄清，则执行 `CapabilityRoutingService.route(agentId, query)`
3. 基于路由结果决定本轮工具集
4. 构造 `AgentRuntimeExtensions`
5. 执行 `ManagedAgent.run(...)`

### 9.2 为什么必须在这里接入

因为此时已经有：

- `agentId`
- `query`
- `threadId`
- `runtimeRequestId`

且还没有真正构造最终 `toolCallbacks`，非常适合：

- 裁剪工具集
- 注入路由说明
- 记录 explain

---

## 10. 工具暴露策略

### 10.1 对 Agent 暴露的指标工具

只暴露三个稳定工具：

1. `metric.catalog.search`
2. `metric.catalog.describe`
3. `metric.query.execute`

### 10.2 各工具职责

#### `metric.catalog.search`

职责：

- 根据用户自然语言问题检索候选指标
- 返回候选指标清单

输入建议：

```json
{
  "query": "近7天GMV趋势",
  "limit": 5
}
```

输出建议：

```json
{
  "summary": "共匹配到 3 个候选指标",
  "candidates": [
    {
      "metricCode": "gmv_trend",
      "metricName": "GMV",
      "score": 0.97,
      "description": "成交总额",
      "supportedGranularities": ["DAY", "WEEK", "MONTH"],
      "supportedDimensions": ["shopId", "region", "category"]
    }
  ]
}
```

#### `metric.catalog.describe`

职责：

- 返回某个指标的完整定义
- 用于在调用前确认参数结构、粒度、维度、单位

输入建议：

```json
{
  "metricCode": "gmv_trend"
}
```

#### `metric.query.execute`

职责：

- 执行统一指标查询
- 由服务端负责映射成实际 HTTP 请求

输入建议：

```json
{
  "metricCode": "gmv_trend",
  "timeRange": {
    "start": "2026-06-01",
    "end": "2026-06-07",
    "granularity": "DAY",
    "timezone": "Asia/Shanghai"
  },
  "groupBy": [],
  "filters": [],
  "orderBy": [],
  "limit": 100,
  "format": "TIMESERIES"
}
```

输出建议：

```json
{
  "summary": "已查询指标 GMV，返回 7 行结果",
  "columns": [
    { "name": "date", "type": "string" },
    { "name": "gmv", "type": "number", "unit": "yuan" }
  ],
  "rows": [
    { "date": "2026-06-01", "gmv": 1000.12 }
  ],
  "metadata": {
    "metricCode": "gmv_trend",
    "metricName": "GMV",
    "sourceType": "metric-system"
  }
}
```

### 10.3 不允许的实现方式

以下做法不允许采用：

1. 不要直接把 Swagger 中每个 operation 原样暴露成独立工具。
2. 不要让 Agent 拼任意 HTTP URL 并直接发请求。
3. 不要把指标系统结果直接透传给前端而不做归一化。

---

## 11. Swagger/OpenAPI 元数据同步设计

### 11.1 目标

通过稳定 Swagger 地址自动构建本地指标目录，支撑：

1. 路由判定
2. 指标搜索
3. 指标参数校验
4. HTTP 请求构造

### 11.2 建议新增模块

建议新增包：

- `com.alibaba.cloud.ai.dataagent.capability.metric.openapi`

建议新增类：

1. `MetricOpenApiSyncService`
   - 拉取 Swagger 文档
   - 计算 hash
   - 解析并刷新本地索引

2. `MetricOpenApiParser`
   - 将 OpenAPI 结构转换为 `MetricDefinition`

3. `MetricDefinition`
   - 描述一个指标能力的统一对象

4. `MetricCatalogIndex`
   - 负责检索、召回、按 code 读取定义

### 11.3 同步时机

建议支持三种时机：

1. 启动时预热一次
2. 定时刷新
3. 手动刷新接口

### 11.4 `MetricDefinition` 建议字段

至少包括：

- `metricCode`
- `metricName`
- `aliases`
- `description`
- `operationId`
- `httpMethod`
- `path`
- `requestSchema`
- `responseSchema`
- `supportedGranularities`
- `supportedDimensions`
- `supportedFilters`
- `examples`
- `tags`
- `lastSyncTime`

### 11.5 解析约束

实现智能体必须遵守：

1. 如果 Swagger 文档无法解析为完整 `MetricDefinition`，该指标不应进入可调用目录。
2. 若整份 Swagger 拉取失败，应保留上一次成功版本的缓存，不要清空能力。
3. 若没有任何有效指标定义，应使指标能力降级为不可用，并记录 warning。

---

## 12. 通用 Capability 框架设计

### 12.1 建议新增接口

包建议：

- `com.alibaba.cloud.ai.dataagent.capability`

接口建议：

```java
public interface CapabilityProvider {

    String capabilityId();

    boolean enabledForAgent(String agentId);

    CapabilityRouteResult route(String agentId, String query);

    Map<String, ToolCallback> getToolCallbacks(String agentId);

    void refreshMetadata();
}
```

### 12.2 建议新增核心类

1. `CapabilityRouteType`
   - `METRIC_ONLY`
   - `DB_ONLY`
   - `MIXED`
   - `UNKNOWN`

2. `CapabilityRouteResult`
   - `routeType`
   - `score`
   - `matchedCapabilityId`
   - `matchedTargets`
   - `reason`

3. `CapabilityRegistry`
   - 聚合所有 `CapabilityProvider`

4. `CapabilityRoutingService`
   - 调用 registry 中各 provider 的 `route()`
   - 产出最终路由决策

### 12.3 指标系统实现

指标系统实现为：

- `MetricCapabilityProvider implements CapabilityProvider`

职责：

1. 管理指标能力启用状态
2. 提供指标工具
3. 执行指标问题路由
4. 刷新 Swagger 元数据

---

## 13. 与技能体系的关系

### 13.1 产品层仍叫“技能”

为了保持产品概念一致，建议增加一个内置 skill：

- `builtin-metric-system`

这样前端或管理界面仍然可按“技能开关”配置 Agent 是否启用指标系统。

### 13.2 技术层用 Capability 实现

不要只写一个新的 `SkillBoundToolProvider` 就结束。

原因：

1. 技能体系只解决“启用哪些工具”
2. 本次还需要解决：
   - Swagger 同步
   - 路由
   - 能力健康状态
   - HTTP 调用适配
   - explain 记录

因此建议：

- 产品开关：走 skill
- 技术实现：走 capability

### 13.3 最终关系

建议关系如下：

1. Agent 开启 `builtin-metric-system`
2. `CapabilityRegistry` 判断该 Agent 可使用 `metric-system`
3. `CapabilityRoutingService` 在运行时决定本轮是否使用该能力
4. 再决定是否注入指标工具

---

## 14. 运行时工具集裁剪规则

### 14.1 `METRIC_ONLY`

保留：

- 通用工具
- 指标工具

去掉：

- `datasource.*.search`
- `sql_guard.check`
- 与数据库查数直接相关的工具

### 14.2 `DB_ONLY`

保留：

- 当前数据库工具链

去掉：

- 指标工具

### 14.3 `MIXED`

保留：

- 指标工具
- 数据库工具

同时必须注入明确约束：

1. 先拆分子问题
2. 标准指标部分走指标系统
3. 非指标补充部分走数据库
4. 不允许用 SQL 重新计算已有标准指标

### 14.4 `UNKNOWN`

建议策略：

- 优先澄清
- 不直接放开所有工具让模型自由尝试

---

## 15. 运行时提示词注入策略

### 15.1 复用现有 `skillInstructions`

当前 [AgentRuntimeExtensionFactory](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentRuntimeExtensionFactory.java#L45-L61) 最后一个参数是 `skillInstructions`，当前为空字符串。

本次建议改造为：

- 将路由结果生成运行时提示词
- 填入 `skillInstructions`

### 15.2 不同路由结果的提示词模板

#### `METRIC_ONLY`

要求提示词表达：

1. 当前问题已判定为指标系统问题
2. 优先使用 `metric.catalog.search / describe / execute`
3. 不允许改用数据库 SQL 自行计算标准指标

#### `DB_ONLY`

要求提示词表达：

1. 当前问题已判定为数据库问题
2. 使用现有 datasource explorer、semantic、sql_guard 工具链
3. 不要尝试指标系统工具

#### `MIXED`

要求提示词表达：

1. 问题需要拆分
2. 指标子问题必须走指标工具
3. 非指标子问题再走数据库
4. 最终统一汇总回答

### 15.3 注意事项

不要改写 `commonagent.md` 中已有数据库工具规则，只做“增量注入”。

原因：

- 现有规则已比较完整
- 本次应尽量减少对原能力的回归风险

---

## 16. 指标查询执行服务设计

### 16.1 目标

将 `metric.query.execute` 的统一输入映射成指标系统的实际 HTTP 请求。

### 16.2 建议新增类

包建议：

- `com.alibaba.cloud.ai.dataagent.capability.metric.execution`

类建议：

1. `MetricQueryExecutionService`
2. `MetricRequestMapper`
3. `MetricHttpClient`
4. `MetricResponseNormalizer`

### 16.3 职责拆分

#### `MetricRequestMapper`

职责：

- 将统一 `MetricQueryRequest` 映射成实际 HTTP 请求
- 根据 `MetricDefinition` 决定 path、method、request body

#### `MetricHttpClient`

职责：

- 调用指标系统 HTTP 接口
- 使用现有 `WebClient.Builder`
- 封装超时、异常映射、日志

#### `MetricResponseNormalizer`

职责：

- 将实际响应转成统一输出结构：
  - `summary`
  - `columns`
  - `rows`
  - `metadata`

---

## 17. 统一结果结构

无论结果来自：

- 指标系统
- 数据库查询

都建议尽量归一化为统一结构，字段建议如下：

```json
{
  "summary": "本次查询摘要",
  "columns": [
    { "name": "date", "type": "string", "description": "日期" }
  ],
  "rows": [
    { "date": "2026-06-01", "value": 100 }
  ],
  "metadata": {
    "sourceType": "metric-system",
    "metricCode": "gmv_trend",
    "metricName": "GMV",
    "unit": "yuan",
    "costMs": 120
  }
}
```

统一结构的价值：

1. LLM 更容易总结结果
2. explain 更容易展示
3. 后续混合问题结果汇总更方便

---

## 18. Explain 链路扩展

### 18.1 目标

让前端或排查方能够知道：

1. 本轮为什么被判成指标问题
2. 命中了哪个指标定义
3. 调用了哪个外部接口
4. 指标系统返回了什么摘要

### 18.2 建议扩展 `AnswerTraceExplainStore`

新增能力：

1. `recordCapabilityRouting(...)`
2. `recordMetricCatalogSearch(...)`
3. `recordMetricQueryResult(...)`

建议新增展示字段：

- `routeType`
- `capabilityId`
- `matchedMetrics`
- `metricToolSteps`

### 18.3 记录时机

1. 路由结束后记录路由结果
2. `metric.catalog.search` 调用后记录候选指标
3. `metric.query.execute` 调用后记录结果摘要

---

## 19. 配置设计

### 19.1 第一阶段建议仅使用配置文件

建议先使用 `application.yml`，新增配置：

```yaml
dataagent:
  capabilities:
    metric-system:
      enabled: true
      swagger-url: http://metric-system.xxx/api-docs
      base-url: http://metric-system.xxx
      refresh-interval-seconds: 1800
      route-threshold: 0.75
```

### 19.2 第二阶段可落库

若后续要支持多个外部系统实例，再新增表设计。第一阶段不强制建表。

---

## 20. 包结构建议

建议新增如下包结构：

```text
com.alibaba.cloud.ai.dataagent.capability
com.alibaba.cloud.ai.dataagent.capability.metric
com.alibaba.cloud.ai.dataagent.capability.metric.model
com.alibaba.cloud.ai.dataagent.capability.metric.openapi
com.alibaba.cloud.ai.dataagent.capability.metric.routing
com.alibaba.cloud.ai.dataagent.capability.metric.execution
com.alibaba.cloud.ai.dataagent.capability.metric.tool
```

建议新增类清单如下：

```text
CapabilityProvider
CapabilityRegistry
CapabilityRoutingService
CapabilityRouteType
CapabilityRouteResult

MetricCapabilityProvider
MetricDefinition
MetricCatalogIndex
MetricOpenApiSyncService
MetricOpenApiParser
MetricRequestMapper
MetricHttpClient
MetricResponseNormalizer
MetricQueryExecutionService

MetricCatalogSearchToolProvider
MetricCatalogDescribeToolProvider
MetricQueryExecuteToolProvider
```

注：

- 如果希望减少类数量，也可以把三个指标工具收敛在一个 `MetricToolProvider` 中返回多个 `ToolCallback`。
- 但职责上仍应保持“搜索 / 描述 / 执行”分离。

---

## 21. 代码改造点清单

本节给出建议的最小改造点。

### 21.1 `AiAgentRuntimeServiceImpl`

需要改造：

1. 注入 `CapabilityRoutingService`
2. 在 clarify 后执行路由
3. 基于路由结果裁剪工具集
4. 生成 runtime routing instructions
5. 记录 explain

### 21.2 `AgentRuntimeExtensionFactory`

需要改造：

1. 支持接收外部传入的 `skillInstructions`
2. 不再固定传空字符串

### 21.3 `LocalSkillServiceImpl`

建议改造：

1. 新增一个内置 skill：`builtin-metric-system`
2. skill 文案中说明：
   - 指标问题优先使用指标工具
   - 不要用数据库 SQL 自行计算标准指标

### 21.4 `AnswerTraceExplainStore`

需要改造：

1. 新增路由记录能力
2. 新增指标工具步骤记录能力

### 21.5 新增定时同步入口

建议新增：

- 启动时预热
- 定时刷新 Swagger 索引

---

## 22. 实现顺序（必须按顺序）

为了降低风险，建议按以下顺序实施，不要一次性同时改所有点。

### 阶段 1：能力框架与指标目录

目标：

1. 建立 `CapabilityProvider` 框架
2. 完成 Swagger 同步
3. 构建 `MetricCatalogIndex`

验收：

- 系统启动后能成功加载指标目录
- 可通过单测验证指标检索结果

### 阶段 2：指标工具与 HTTP 执行

目标：

1. 实现 `metric.catalog.search`
2. 实现 `metric.catalog.describe`
3. 实现 `metric.query.execute`
4. 完成结果归一化

验收：

- 三个工具可独立调用
- 指标系统异常时能返回明确错误

### 阶段 3：运行时路由接入

目标：

1. 在 `AiAgentRuntimeServiceImpl` 接入路由
2. 基于路由结果裁剪工具集
3. 注入 runtime instructions

验收：

- 指标问题不再暴露数据库工具
- 数据库问题不再暴露指标工具

### 阶段 4：技能开关与 explain

目标：

1. 增加 `builtin-metric-system`
2. Agent 可配置是否启用指标能力
3. explain 可显示路由和指标调用摘要

验收：

- 前端能通过 Agent skill 配置启停指标能力
- explain 接口可查询到路由结果

### 阶段 5：混合问题增强

目标：

1. 支持 `MIXED`
2. 提示词中要求先拆分再执行
3. 汇总指标结果与数据库结果

验收：

- 简单混合问题能稳定输出组合结果

---

## 23. 测试要求

### 23.1 单元测试

必须覆盖：

1. `MetricOpenApiParser`
2. `MetricCatalogIndex.search`
3. `CapabilityRoutingService.route`
4. `MetricRequestMapper`
5. `MetricResponseNormalizer`

### 23.2 集成测试

必须覆盖：

1. Swagger 拉取成功
2. Swagger 拉取失败但保留旧缓存
3. 指标接口成功返回
4. 指标接口返回空结果
5. 指标接口返回 5xx

### 23.3 回归测试

必须验证：

1. 原数据库问数链路不受影响
2. `datasource.*.search` 仍能正常执行
3. explain 原有结构不被破坏

---

## 24. 验收标准

本方案实施完成后，必须满足以下验收条件：

1. 用户问标准指标问题时，系统调用指标工具，而不是数据库 SQL。
2. 用户问数据库明细或表结构问题时，系统仍走当前数据库工具链。
3. Agent 未开启指标技能时，不会启用指标能力。
4. 系统能从 Swagger 自动构建指标目录。
5. 指标系统异常时，系统能给出明确错误，不 silently fallback 到 SQL 重算指标。
6. explain 中能看到：
   - 路由结果
   - 匹配到的指标
   - 指标调用摘要

---

## 25. 风险与处理策略

### 风险 1：Swagger 文档质量不足

处理：

- 解析时进行完整性校验
- 不完整的指标不进入目录

### 风险 2：路由误判

处理：

- V1 优先保守策略
- 低置信度走 `UNKNOWN` 并澄清

### 风险 3：指标系统异常

处理：

- 不改走数据库重算
- 直接返回“指标系统暂时不可用”

### 风险 4：改造影响原链路

处理：

- 路由接入前先完成能力框架和工具侧联调
- 通过回归测试保护数据库能力

---

## 26. 对实现智能体的执行约束

后续实现智能体必须遵守以下约束：

1. 优先复用现有基础设施，不要重写整个 Agent 运行时。
2. 新增能力必须采用可扩展的 `CapabilityProvider` 设计，不允许写成“指标系统专用 if/else 散逻辑”。
3. 运行时工具裁剪必须在 Agent 执行前完成，不允许单纯依赖 prompt 约束。
4. 指标工具命名保持稳定，不允许把 Swagger 原始 operationName 直接暴露给 Agent。
5. 所有新能力都要接 explain，不允许成为黑盒调用。
6. 第一阶段配置优先走 `application.yml`，不要一开始就引入复杂落库。

---

## 27. 推荐的第一版交付边界

如果需要先做 MVP，建议只交付以下边界：

1. `CapabilityProvider` 通用框架
2. `MetricCapabilityProvider`
3. Swagger 同步与目录索引
4. 三个指标工具
5. `AiAgentRuntimeServiceImpl` 路由接入
6. `builtin-metric-system` skill
7. explain 路由与指标调用记录

先不做：

1. 复杂混合计划编排
2. 多外部系统并行接入
3. 落库配置中心

---

## 28. 总结

本方案不是“给当前系统新增一个指标接口调用工具”这么简单，而是要完成一次面向未来的能力架构升级。

本次实现的正确方向是：

1. 在运行时引入能力路由层
2. 将指标系统沉淀为标准化 `CapabilityProvider`
3. 通过 Swagger 构建本地指标目录
4. 对 Agent 只暴露稳定高层工具
5. 通过技能开关控制 Agent 可用能力
6. 将能力路由与外部调用纳入 explain 链路

若按本文实施，后续再接入其他外部系统时，可以直接复用：

- Capability 注册
- 路由
- 元数据同步
- 工具暴露
- explain 记录

而不需要再次设计一套新的接入模式。
