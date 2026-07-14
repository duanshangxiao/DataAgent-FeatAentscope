# DataAgent SSE 流式接口与 AgentScope 工具体系梳理

本文整理了围绕 DataAgent 流式接口（SSE）核心链路、`executeAgent` 内部执行、工具（Tool）注册与使用方式，以及工具入参 Schema 约定的讨论结论，便于后续开发与排查。

---

## 1. SSE 接口核心流程（Controller 侧）

以 [DataAgentController.streamSearch](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/DataAgentController.java#L51-L100) 为例，整体职责是：

1. **参数与会话校验**
   - `agentId` 转 Long，非法则 400：[parseAgentId](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/DataAgentController.java#L102-L109)
   - 校验 threadId 与 agent 的会话关系：`chatSessionService.requireSessionForAgent(threadId, numericAgentId)`。
2. **设置 SSE 响应头**：no-cache、keep-alive、CORS 等。
3. **创建 sink/构造请求**
   - 创建 `Sinks.many().unicast().onBackpressureBuffer()`。
   - 组装 `AgentRequest`（query、runtimeRequestId、clarifyCheckEnabled、humanFeedback 等）。
4. **触发核心执行**：`agentService.graphStreamProcess(sink, request)`（核心调用点）。
5. **返回 Flux 并处理订阅生命周期**
   - `filter`：放行 complete/error；普通消息需要 `data.text` 非空。
   - `doOnCancel/doOnError`：调用 `agentService.stopStreamProcessing(threadId, runtimeRequestId)` 触发取消。

---

## 2. graphStreamProcess：异步执行与 SSE 事件封装（Service 侧）

实现类为 [AiAgentRuntimeServiceImpl.graphStreamProcess](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java#L128-L151)：

- **初始化 runtimeRequestId**：缺失则生成 UUID（threadId 为空直接抛错）。
- **注册运行态**：`runtimeRegistry.register(threadId, runtimeRequestId)`。
- **构造 eventPublisher**：将 `AgentResponse` 包装为 `ServerSentEvent`（event=`message`）并 emit 到 sink，同时记录文本用于“是否重复补发最终答案”的判断。
- **异步执行**：
  - `executeAgent(agentRequest, eventPublisher)` 通过 `Schedulers.boundedElastic()` 异步跑。
  - 成功：`emitSuccess`（必要时补发最终 text，再发 complete 事件并 complete sink）。
  - 失败：`emitError`（若不是取消触发的异常，则发 error 事件并 complete sink）。
- **取消**：`stopStreamProcessing` 仅标记取消：`runtimeRegistry.markCancelled(threadId, runtimeRequestId)`。

---

## 3. executeAgent：模型选择、工具选择、ManagedAgent 执行、澄清拦截与流式输出

核心逻辑在 [AiAgentRuntimeServiceImpl.executeAgent](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java#L216-L287)。

### 3.1 Memory 与可观测性

- 记录 running 线程、打开 explain scope、创建 tracing span。
- 加载会话 memory： [AgentScopeMemoryFactory.create](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeMemoryFactory.java#L33-L46) 通过 threadId 从 native session 载入历史消息（未命中则空 memory）。

### 3.2 澄清拦截（Query Clarify）

- 开关判定：`request.isClarifyCheckEnabled()` 或 `humanFeedbackContent` 非空都会触发澄清评估。
- 评估逻辑： [QueryClarifyService.assess](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/QueryClarifyService.java#L59-L109)
  - 缺少时间范围/指标口径/对比对象/排序依据等维度会加分，分数 ≥ 3 判定 HIGH 并阻断执行。
- 阻断执行：走 [blockForClarification](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java#L308-L330)
  - 直接通过 `eventPublisher.publish(...)` 发出一条澄清提示（nodeName=`AgentScopeRuntime`，带 metadata）。
  - 返回 clarifyText，后续 `emitSuccess` 会通过 `StreamTextTracker` 判断避免重复补发最终文本。

### 3.3 模型选择（ChatModel → AgentScope Model）

- 获取活动模型配置：`modelConfigDataService.getActiveConfigByType(ModelType.CHAT)` 并校验 apiKey/modelName。
- 创建 ChatModel： [DynamicModelFactory.createChatModel](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java#L54-L83)
  - 统一使用 `OpenAiChatModel`，以 `baseUrl` 兼容多厂商。
- 适配成 AgentScope 的 `Model`： [AgentScopeModelFactory](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/AgentScopeModelFactory.java#L31-L34)

### 3.4 ManagedAgent 执行（CommonAgent + ReActAgent）

- 当前 ManagedAgent 固定取 `commonagent`： [ManagedAgentRegistry.getRequired](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/template/ManagedAgentRegistry.java#L35-L41)
- 执行实现： [CommonAgent.run](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/template/CommonAgent.java#L42-L78)
  - 用 `ReActAgent.builder()` 注入 model、toolkit、memory、toolExecutionContext、skillBox、hooks。
  - `agent.call(userMsg).block(timeout)` 产出最终 Msg。

### 3.5 流式输出如何产生（Hook → eventPublisher → sink）

- 运行时扩展由 [AgentRuntimeExtensionFactory.create](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentRuntimeExtensionFactory.java#L45-L61) 组装：
  - `Toolkit`：由 toolCallbacks 构造（见后文）。
  - `ToolExecutionContext`：注册 `graphRequest` 与 `AgentRuntimeRequestMetadata`（用于工具执行上下文）。
  - `hooks`：由 [AgentScopeHookFactory](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeHookFactory.java#L24-L41) 创建。
- 若存在 `eventPublisher`，会挂载 [AgentScopeStreamingHook](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeStreamingHook.java#L49-L66)：
  - ReasoningChunkEvent → `planner-reasoning` 节点推送增量文本。
  - PreActing/ActingChunk/PostActing → `tool:<toolName>` 推送“调用工具/工具输出”文本。
- `eventPublisher` 最终在 `graphStreamProcess` 中封装为 SSE 并 emit。

---

## 4. 工具体系：Common 工具 vs Agent-Scoped 工具

工具回调取自 [AgentScopeToolkitFactory.getToolCallbacks](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeToolkitFactory.java#L63-L93)，由两部分合并：

### 4.1 Common 工具（全局通用）

- **定义**：来自 Spring 容器中的 `ToolCallback` Bean 与 `ToolCallbackProvider` Bean（provider 可批量提供 ToolCallback）。
- **收集时机**：
  - 启动完成（ApplicationReadyEvent）预热： [warmUpCommonToolCallbacks](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeToolkitFactory.java#L67-L70)
  - 首次使用也可触发懒加载。
- **排除**：标注了 `@McpServerTool` 的 Bean 会被排除： [McpServerToolUtil.excludeMcpServerTool](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/McpServerToolUtil.java#L27-L34)
  - 例如 MCP 的 ToolCallbackProvider 在 [McpServerConfig](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java#L32-L36) 中被标注排除（用于避免循环依赖）。
- **当前代码库静态检索结论**：目前未发现可被收集进 common snapshot 的 ToolCallback/Provider（MCP provider 被排除；未见独立 ToolCallback Bean），因此 common 工具集合在当前实现下为空。

### 4.2 Agent-Scoped 工具（按 agentId 动态提供）

- **定义**：实现 [AgentScopedToolProvider](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/AgentScopedToolProvider.java) 的组件，按 agentId 返回 ToolCallback。
- **汇总入口**： [AgentScopedToolCatalogService](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/AgentScopedToolCatalogService.java#L31-L48)
- **当前系统包含的 Agent-Scoped 工具（4 个 Provider）**：
  1. 数据源探索：DatasourceExplorerToolProvider（工具名动态为 `datasource.<slug>.search`）。
  2. SQL 守卫：SqlGuardToolProvider（`sql_guard.check`）。
  3. 语义检索：SemanticModelToolProvider（`semantic_model.search`）。
  4. 领域知识检索：DomainBusinessKnowledgeToolProvider（`domain_business_knowledge.search`）。

---

## 5. 以 DatasourceExplorerToolProvider 为例：Agent-Scoped Tool 如何定义与使用

文件：[DatasourceExplorerToolProvider](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerToolProvider.java)

### 5.1 定义（ToolDefinition + ToolCallback）

- Provider 根据 agentId 找“当前活动数据源”，决定是否提供工具，并动态生成工具名：`datasource.<slug>.search`。
- 用 `ToolDefinition.builder()` 定义：
  - `name`：工具名
  - `description`：自然语言使用说明（约束/建议/可见表范围提示等）
  - `inputSchema`：入参 schema（JSON Schema 风格字符串）

### 5.2 使用（从调用到执行）

1. `executeAgent` 获取 toolCallbacks 并注入到 Toolkit。
2. Agent/模型生成工具调用后，会走适配层 [SpringToolCallbackAgentAdapter](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/SpringToolCallbackAgentAdapter.java#L67-L111)：
   - 将调用入参序列化为 JSON payload；
   - 构造 `ToolContext`，把 `graphRequest` / `runtimeRequestMetadata` 等上下文带入；
   - 调用 `ToolCallback.call(payload, toolContext)`。
3. DatasourceExplorer 的 ToolCallback：
   - 解析 JSON 为 `DatasourceExplorerRequest`；
   - 在 `validateRequest` 做硬校验（action 分支必填规则）；
   - 通过 [ToolContextRequestResolver.resolveGraphRequest](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/ToolContextRequestResolver.java#L31-L55) 从 ToolContext 提取 `AgentRequest`；
   - 调用 `datasourceExplorerService.execute(agentId, request, agentRequest)` 并返回 JSON 结果。

---

## 6. 工具 Schema 与描述：规范是什么？给谁看的？在哪里被 LLM “解析”？

### 6.1 规范是什么

- `ToolDefinition.inputSchema` 是一个字符串，通常用 JSON Schema 风格描述参数结构（你们当前写法：object/properties/required/enum/description 属于最兼容的子集）。
- `ToolDefinition.description` 用自然语言描述“何时使用/约束/注意事项”，用于提高模型调用质量。

### 6.2 给谁看的

- **主要给 LLM/Agent 的 tool-calling 机制看的**：用于引导模型生成正确的 tool call 参数。
- **也会被 AgentScope 侧读取**：在 `SpringToolCallbackAgentAdapter.getParameters()` 里解析 schema，用于对外暴露工具参数描述；解析失败会 fallback（不影响工具硬校验）。

### 6.3 在哪里被 LLM 解析

系统不会在本地“语义解析 schema”，而是把 `ToolCallback[]` 塞到 Spring AI 的 `ToolCallingChatOptions` 中，随模型请求发送给 LLM，由模型侧基于 tools schema 生成 tool calls：

- [SpringAiAgentScopeModel.buildChatOptions](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/SpringAiAgentScopeModel.java#L146-L177)
- [SpringAiAgentScopeModel.doStream](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/SpringAiAgentScopeModel.java#L79-L91)

---

## 7. 业内约定俗成的 schema 规范（建议落地方式）

结论：业内最通用的是 **JSON Schema（常用 draft 的子集）**，并尽量贴近各家 LLM 工具调用可稳定理解的那部分能力。

建议遵循的常用子集：

- 根对象：`type=object` + `properties` + `required`
- 常用类型：string/integer/number/boolean/array/object
- 枚举：`enum`
- 适度约束：min/max、pattern（避免过复杂）
- 控制嵌套深度（建议 ≤ 2）

重要实践：

- schema 主要用于**引导模型正确调用**；真正强校验与安全规则应落在工具实现（你们的 `validateRequest`/只读 SQL 校验等）中。

---

## 8. Prompt / Skill 提示词文档：作用、加载时机与生效范围

本项目中“提示词文档”主要分两类：**agent template prompt**（如 `prompts/commonagent.md`）与 **skill prompt**（如 `agent-skills/**/SKILL.md`）。两者都会影响模型行为，但加载与生效方式不同。

### 8.1 `src/main/resources/prompts/commonagent.md`（模板系统提示词）

- **加载入口**：`CommonAgent` 在类初始化阶段通过 `PromptLoader.loadPrompt("commonagent","md")` 读取并缓存为静态常量：  
  - [CommonAgent.SYSTEM_PROMPT](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/template/CommonAgent.java#L35-L36)  
  - [PromptLoader.loadPrompt](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptLoader.java#L33-L68)
- **加载来源**：classpath 资源目录 `prompts/`（即 `data-agent-management/src/main/resources/prompts/`）。
- **生效范围**：作为每次 `ReActAgent` 的 base system prompt，与自定义 systemPrompt、skillInstructions 合并后传入模型：  
  - [CommonAgent.defaultSystemPrompt](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/template/CommonAgent.java#L80-L90)
- **热更新特性**：由于 `SYSTEM_PROMPT` 是 `static final` 且 `PromptLoader` 有缓存，运行中直接改 `commonagent.md` 通常不会生效；需要重启进程（或重新加载 classpath 资源）。

### 8.2 `./agent-skills/**/SKILL.md`（本地 skill 提示词）

- **存储位置**：默认 `./agent-skills`，可通过 `dataagent.skills.localPath` 配置；后端会将其解析为绝对路径：  
  - [AgentSkillProperties.getLocalBasePath](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/properties/AgentSkillProperties.java#L29-L47)
- **加载入口**：当某个 Agent 运行时会创建 `SkillBox`，并根据该 Agent 启用的 skillId 列表读取对应 `SKILL.md`：  
  - [AgentScopeSkillBoxFactory.create](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeSkillBoxFactory.java#L52-L76)  
  - [LocalSkillServiceImpl.loadAgentSkills](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/skill/impl/LocalSkillServiceImpl.java#L179-L200)
- **生效范围**：skill 以 `SkillBox` 形式注入 `ReActAgent`：  
  - [CommonAgent.run](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/template/CommonAgent.java#L63-L66)
- **热更新特性**：`LocalSkillServiceImpl.loadAgentSkills` 每次会从文件读 `SKILL.md` 并构造 skill（未做进程级缓存），一般修改后在下一次请求运行时生效。
- **内置 skill 引导生成**：首次访问 skill 存储目录时会自动引导生成 builtin skill（如果缺失或内容不一致会重写）：  
  - [LocalSkillServiceImpl.ensureStorageReady / bootstrapBuiltinSkills](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/skill/impl/LocalSkillServiceImpl.java#L221-L236)

---

## 9. 提示词中的“方法名 / 字典值”代表什么？如何影响运行？

提示词里出现的诸如 `domain_business_knowledge.search`、`sql_guard.check`、`PREVIEW_ROWS` 并不是 Java 方法调用，而是面向 LLM 的“可调用能力标识”：

### 9.1 `domain_business_knowledge.search`：工具名（ToolDefinition.name）

- 后端通过 `ToolDefinition.name("domain_business_knowledge.search")` 注册工具回调：  
  - [DomainBusinessKnowledgeToolProvider](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/knowledge/DomainBusinessKnowledgeToolProvider.java#L29-L46)
- 模型在 ReAct 推理中选择调用该工具时，会生成 tool call；运行时通过 `Toolkit` 分发到对应 `ToolCallback` 执行（见第 5.2 节）。
- 提示词对其影响主要是“软约束”：例如建议只有在明显依赖业务口径时才调用，减少无意义检索与幻觉风险。

### 9.2 `PREVIEW_ROWS`：工具入参枚举值（inputSchema 的 `action`）

- `PREVIEW_ROWS` 是数据源探索工具的 `action` 枚举之一：  
  - [DatasourceExplorerAction.PREVIEW_ROWS](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerAction.java#L21-L34)  
  - [DatasourceExplorerToolProvider.INPUT_SCHEMA enum](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerToolProvider.java#L41-L76)
- 运行时会做“硬校验”：当 `action=PREVIEW_ROWS` 时必须提供 `tableName`，否则直接报错：  
  - [DatasourceExplorerToolProvider.validateRequest](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerToolProvider.java#L186-L206)
- 提示词中“`PREVIEW_ROWS` 不是默认探索步骤”的作用是引导模型更倾向于先用 schema/relations 推理，减少不必要的数据预览调用。

---

## 10. 动态 ToolName（`datasource.<slug>.search`）下提示词与 skill 如何写、如何生效？

`DatasourceExplorerToolProvider` 的工具名不是固定值，而是根据 Agent 的活动数据源动态生成：  
- [DatasourceExplorerToolProvider.getToolCallbacks](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerToolProvider.java#L84-L100)

### 10.1 系统如何让模型“知道”动态 toolName

- 在每次请求执行 `executeAgent` 时，系统会按 agentId 收集工具回调并构建 Toolkit（common + agent-scoped）：  
  - [AgentScopeToolkitFactory.collectToolCallbacks](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentScopeToolkitFactory.java#L88-L93)
- 最终会把当次可用的 tools（包含动态生成的 `datasource.<slug>.search`）随模型请求发送给 LLM；模型据此生成正确的 tool call（见第 6.3 节）。

### 10.2 Prompt / Skill 在动态 toolName 下的“使用方式”

- Prompt/skill 不需要硬编码 `datasource.<slug>.search` 的完整字符串；更稳健的写法是：
  - 用“数据源探索工具/统一探索工具”描述其职责与约束；
  - 强调稳定入参字段与枚举（`action=LIST_TABLES/GET_TABLE_SCHEMA/PREVIEW_ROWS/SEARCH`，以及 `tableName/sql/query/limit`）；
  - 避免把某个 slug（由数据源名称推导）写死在示例里，否则一旦数据源重命名导致 slug 变化，提示词示例就会失效。
- 该工具本身的 `description` 已包含关键约束（例如 PREVIEW_ROWS 不是默认前置动作），即使 prompt 未点名 toolName，模型也能在工具卡片里读到约束：  
  - [DatasourceExplorerToolProvider.buildDescription](file:///Users/dsx/devTools/ai/data-agent260104/remote/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/tool/datasource/DatasourceExplorerToolProvider.java#L124-L140)

