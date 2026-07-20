# 教训日志

> 每次发现并修复一个 bug、踩到一个坑、或者碰到一个非直觉的设计约束后，在这里追加一条。
> 格式：`## 日期: 一句话标题` → 现象 → 根因 → 修复/规避方案

---

## 2025-07: OpenAPI 解析器不识别 `*/*` media type

- **现象**：MetricCapabilityProvider 启动后 `definitions` 始终为空，指标目录永远无法就绪
- **根因**：OpenAPI 解析器只匹配 `application/json` 类型的响应，第三方指标系统返回的 content-type 为 `*/*`，解析器直接跳过
- **修复**：`resolveContentSchema` 中按 `application/json` → `*/*` → 遍历所有 media type 的顺序兜底匹配
- **教训**：集成外部 OpenAPI/Swagger 时，不能假设对方遵守 content-type 约定，需要做兼容解析

---

## 2026-07-14: 指标能力集成方案架构评估 — 4 类问题

### P0: 路由召回仅基于关键词匹配，无语义检索
- **现象**：用户自然语言问题（如"上个月销售额怎么样"）无法匹配 OpenAPI 文档中的指标名（如 `revenue_amount`）
- **根因**：`MetricCatalogIndex.search()` 纯字符串包含+ngram 匹配，无语义向量检索
- **修复**：注入 `EmbeddingModel`，新增两阶段检索——先对所有 `MetricDefinition` 做 embedding 向量相似度粗排取 top-k，再在精排阶段复用现有关键词评分逻辑
- **改动**：`MetricCatalogIndex` 新增 embedding 索引，`MetricCapabilityProperties` 加 `embeddingEnabled` 开关

### P1: 无熔断器保护 + 路由后无降级回退
- **现象**：指标系统宕机时每个请求阻塞 10 秒超时；METRIC_ONLY 路由下工具被裁剪无法回退 DB
- **根因**：`executeHttp()` 无熔断保护，`isToolAllowed()` 严格按路由类型裁剪工具集
- **修复**：新增 `MetricCircuitBreaker`（轻量实现，计数失败→OPEN→半开探测→CLOSE）。CB OPEN 时 `route()` 强制返回 ABSTAIN；运行时指令中增加"指标系统不可用时允许 DB 近似计算"的提示
- **改动**：新建 `MetricCircuitBreaker.java`，修改 `route()` / `executeHttp()` / `buildRuntimeInstructions()`

### P2: 评分权重硬编码 + enabledForAgent 每次查 DB
- **现象**：评分各维度权重（0.45/0.30/0.15/0.12）、路由阈值（0.75）、澄清阈值系数（0.6）均硬编码，无法调优；每次请求查一次 agent-skill 绑定
- **根因**：评分逻辑中魔法数字直接写死；`enabledForAgent()` 无缓存
- **修复**：权重/阈值外置为 `MetricCapabilityProperties` 字段；`enabledForAgent()` 引入 Caffeine 本地缓存（TTL=60s）
- **改动**：`MetricCapabilityProperties` + `MetricCapabilityProvider` + pom.xml 加 caffeine

### P3: isMetricOperation 关键词硬编码
- **现象**：判断是否为指标接口的关键词列表 `["metric","gmv","dau"]` 硬编码，新业务指标无法自动识别
- **根因**：`isMetricOperation()` 中 keywords 为固定列表
- **修复**：关键词列表外置为 `MetricCapabilityProperties.metricKeywords` 配置项，默认值保留原有列表
- **改动**：`MetricCapabilityProperties` + `MetricOpenApiParser`

---

## 2026-07-15: 指标目录代码卫生问题修复（4 项）

### 1. searchableText 遗漏 metricCode 和 metricName
- **现象**：embedding 向量和关键词匹配的 haystack 文本中不包含 `metricCode()` 和 `metricName()`，这两个字段是 OpenAPI 文档中最重要的标识字段却未纳入检索
- **根因**：`searchableText()` 只拼接了 `summary`、`description`、`operationId`、`path`、`aliases`、`tags`、`requestParameters`
- **修复**：在 `searchableText()` 开头追加 `metricCode()` 和 `metricName()`

### 2. @Scheduled 使用脆弱 SpEL 解析字符串配置
- **现象**：`fixedDelayString = "#{T(java.lang.Long).parseLong('${...:1800}') * 1000}"` runtime 解析，属性名以字符串硬编码在 SpEL 中
- **根因**：`fixedDelay` 不支持 expression，工程上走了 SpEL 绕路
- **修复**：新增 `refreshIntervalMillis()` 方法，SpEL 改为 `"#{@metricOpenApiSyncService.refreshIntervalMillis}"`

### 3. circuitBreakerHalfOpenMaxCalls 类型不一致
- **现象**：`MetricCapabilityProperties` 中为 `long`（默认 2），`MetricCircuitBreaker.configure()` 接收 `int`，调用方做了 `(int)` 强制转换
- **根因**：属性定义时未注意与目标方法签名对齐
- **修复**：`circuitBreakerHalfOpenMaxCalls` 改为 `int`，移除调用方 `(int)` 转换

### 4. embeddingCoarseRank 硬编码 0.3 相似度阈值
- **现象**：`embeddingCoarseRank()` 中 `.filter(entry -> entry.getValue() > 0.3D)` 硬编码，无法根据实际数据分布调优
- **根因**：阈值未外置为配置项
- **修复**：新增 `MetricCapabilityProperties.embeddingMinSimilarity`（默认 0.3），`embeddingCoarseRank` 引用配置项

---

## 2026-07-15: 指标能力系统可观测性缺失

- **现象**：指标目录刷新成功/失败、定义数量、熔断器状态无法从外部查询，只能通过应用日志观察。指标目录静默退化（如定时刷新失败导致 catalog 过期）无法被监控系统告警。
- **根因**：项目无 Micrometer/Actuator 依赖，MetricCapability 组件状态仅通过 SLF4J 日志记录。
- **修复**：新增 `MetricCapabilityStatus` JMX MBean（零依赖），暴露 6 个可查询属性：`ready`、`definitionCount`、`lastRefreshSuccessTime`、`lastRefreshFailureTime`、`lastRefreshError`、`circuitBreakerState`。分别接入 `MetricOpenApiSyncService`、`MetricCircuitBreaker` 的生命周期。
- **教训**：[CHECKPOINT] 后续 capapability provider 新增时同步接入 `MetricCapabilityStatus`；如果将来引入 Micrometer，应优先将 MBean 属性迁移为 Prometheus gauge。

---

## 2026-07-15: 指标检索架构重构 — PGVector 替代自研检索

- **背景**：原 `MetricCatalogIndex` 自研了两阶段检索（embedding 粗排 + 关键词精排），在 OpenAPI 技术文档而非业务语义文本上做匹配，中英文跨语言场景准确率不可靠。
- **方案**：
  1. 指标元数据导入 PGVector（复用项目已有的 `AgentVectorStoreService` + `DocumentConverterUtil`），利用 PGVector 原生 embedding 检索
  2. 混合检索交给已有的 `HybridRetrievalStrategy` 体系（ES 可用时自动并行关键词+向量，不可用时纯向量）
  3. LLM 替代应用层路由决策（`route()` 不再做硬性 `METRIC_ONLY` / `ABSTAIN` 判断，统一返回 `MIXED` 允许多工具共存，LLM 自行判断是否走指标路径）
  4. 解析器接口化（`MetricMetadataParser` → `MetricMetadataParserFactory`），支持未来切换非 OpenAPI 格式
- **删除**：`MetricCatalogIndex.java`、`MetricCatalogSearchCandidate.java`
- **新增**：`MetricMetadataParser.java`、`MetricMetadataParserFactory.java`、`MetricDefinitionLookup.java`、`DocumentConverterUtil.convertMetricToDocument()`
- **改动**：`MetricOpenApiParser` 实现接口、`MetricOpenApiSyncService` 输出到 PGVector、`MetricToolProvider` 搜索切到 `AgentVectorStoreService`、`MetricCapabilityProvider.route()` 简化、`CapabilityRoutingService` 取消工具裁剪

---

## 2026-07-15: Spring Bean 循环依赖 — 两次启动失败

- **现象**：`mvn compile` 通过但 `spring-boot:run` 报 `APPLICATION FAILED TO START`，循环依赖。第一次：`MetricCapabilityProvider → MetricOpenApiSyncService → VectorStore`；第二次：`@Scheduled(fixedDelayString = '#{@metricOpenApiSyncService...}')` 在 bean 初始化期间通过 SpEL 自引用
- **共性根因**：
  1. `compile` 不检查 Spring 容器初始化，循环依赖、bean 后处理器冲突都是运行时故障
  2. 改动 Bean 构造器依赖/注解（`@Scheduled`、`@Component` 注入关系）后没有做容器级验证
- **修复**：
  1. `MetricCapabilityProvider` 删除对 `MetricOpenApiSyncService`（基础设施层）的注入，改用轻量 `MetricCapabilityStatus`
  2. `@Scheduled` SpEL 从 `#{@self}` 改为 `${property:default}000` 属性占位符
- **教训**：[已写入 AGENT.md §6] 修改 Bean 注入/构造器/依赖关系/`@Scheduled`/`@Async`/`@Configuration` 后必须执行 `mvn spring-boot:run` 启动验证，不能在 `compile` 通过后就认为完成

---

## 2026-07-15: 指标能力跨会话故障传染 + 时间解析错误 — 3 个根因

### 1. HTTP 调用失败抛异常导致 LLM 永久避开指标工具
- **现象**：一次指标 HTTP 调用失败后，同一会话后续对话不再使用指标工具，直接走数据库查询。
- **根因**：`MetricQueryExecutionService.execute()` 在 HTTP 异常时 `throw new IllegalStateException`，LLM 收到的是工具调用异常而非 `FALLBACK_TO_DB` 降级结果。LLM 在会话 memory 中"学到"了指标工具不可靠，后续即使系统恢复也不愿调用。
- **修复**：异常捕获后返回 `buildFallbackResult()`（status=FALLBACK_TO_DB），LLM 收到降级提示后可自行决定回退 DB 还是重试。
- **改动**：`MetricQueryExecutionService.java` — `execute()` catch 块改为 return 而非 throw，新增 `buildFallbackResult()` 方法。

### 2. CB OPEN 时路由返回 ABSTAIN 导致 LLM 被告知"不要用指标工具"
- **现象**：熔断器打开后，同一会话甚至新会话都无法使用指标工具。
- **根因**：`MetricCapabilityProvider.route()` 在 CB OPEN 时返回 `ABSTAIN`，导致 `CapabilityRoutingService.buildRuntimeInstructions()` 生成"指标系统不可用，不要尝试指标系统工具"的硬指令。该指令写入 LLM memory，即使 CB 恢复后路由返回 MIXED，LLM 仍受 memory 中旧指令影响。
- **修复**：`route()` 即使 CB OPEN 也只记录 warn 日志，仍返回 `MIXED`。执行层的 CB gating (`allowRequest()`) 保留，但工具本身始终可用，LLM 自行决策。
- **改动**：`MetricCapabilityProvider.java` — `route()` 移除 ABSTAIN 返回分支。

### 3. LLM 不知道当前日期，中文时间模式覆盖不全
- **现象**："近一个月"被解析到 2025 年。
- **根因**：(a) 系统提示词和 runtime instructions 从未注入当前日期，LLM 用训练数据截止时间作为"现在"；(b) `mergeInferredArguments()` 只识别"最近N天"，不识别"近X个月"、"近X周"、"上月"、"本月"等高频中文表达；(c) 旧版推断输出 "NOW-XD" 字符串，而非实际日期，依赖指标端点自行解释。
- **修复**：
  1. `CapabilityRoutingService.buildRuntimeInstructions()` 新增 `buildDateHint()` 注入当前日期和星期。
  2. `MetricQueryExecutionService.mergeInferredArguments()` 新增 `RECENT_MONTHS_PATTERN`、`RECENT_WEEKS_PATTERN`、`LAST_MONTH_PATTERN`、`CURRENT_MONTH_PATTERN`，并基于 `LocalDate.now()` 计算出实际日期字符串（如 `2026-07-15`）。
- **改动**：`CapabilityRoutingService.java` — 新增 import + `buildDateHint()`；`MetricQueryExecutionService.java` — 新增 4 个 Pattern + 日期计算逻辑。
- **教训**：[CHECKPOINT] 任何向 LLM 暴露的指令中如果涉及相对时间解析，必须显式注入当前日期。任何新能力如果涉及参数推断，必须覆盖该领域最常用的自然语言表达，不能只支持 1-2 种模式。

---

## 2026-07-15: 时间处理重构 — 从正则推导到 LLM 驱动

- **背景**：上一轮用 6 个正则 + `LocalDate` 计算的时间推断方案仍然"过于简陋"——每加一种中文表达就要加一个 pattern，且与 `QueryClarifyService`、`SqlVerifyExplainService` 各自的 time pattern 重复、不一致。
- **问题**：用正则替 LLM 做 NLU 是本末倒置。LLM 天然具备理解"近一个月""本月""上季度"等中文相对时间的能力，缺的只是「当前日期」这个锚点。
- **方案**：采用 `builtin-current-time` skill 的设计哲学——**代码只负责提供精确的当前时间，LLM 负责解析自然语言**：
  1. `CapabilityRoutingService.buildRuntimeInstructions()` 新增「时间处理规则」指令块，明确要求 LLM 将相对时间解析为显式日期（yyyy-MM-dd），填入 arguments/timeRange，**禁止使用相对时间字符串**。
  2. `MetricQueryExecutionService.mergeInferredArguments()` 简化：移除 `RECENT_MONTHS_PATTERN`、`RECENT_WEEKS_PATTERN`、`LAST_MONTH_PATTERN`、`CURRENT_MONTH_PATTERN` 及对应的 `LocalDate` 日期计算逻辑。仅保留 `RECENT_DAYS_PATTERN`（兜底最常见模式）+ `DATE_PATTERN`（显式日期提取）+ granularity 关键词匹配。
- **收益**：代码行数减少约 40 行，不再需要为每种中文时间表达维护 pattern。LLM 可自然处理任意语言的时间表达，且日期计算在 LLM 侧完成（结合注入的当前日期锚点），比固定正则更灵活。
- **教训**：[AGENT WRITING] 在 LLM 驱动的系统中，代码层替 LLM 做 NLU 通常是过度工程。正确的模式是：代码提供锚点数据（当前日期），让 LLM 完成解析和推理。

---

## 2026-07-17: 前端改动未审查完整调用链导致渲染回归 + DB 脏数据

- **现象**：
  1. 为 TEXT 节点加上 Markdown 渲染后，页面文字变成"一团糟"（SQL 中的 `*` 被解析为斜体、路径中的 `_` 被解析为强调）
  2. 为工具结果加上折叠功能后，用户反馈"没看到折叠标题"
  3. 编译通过、构建成功，但运行时行为不符合预期

- **根因**：
  1. `formatNodeContent` 被两条路径消费：流式渲染（`generateNodeHtml → v-else`）和 DB 持久化（`saveAssistantNodeMessage → generateNodeHtml → formatNodeContent`）。改动时只审查了流式路径，漏掉了 **DB 写入路径**——`marked.parse()` 把 "Calling tool: X" 包进了 `<p>` 标签、把 SQL 语句中的 `*` 解析为斜体，脏数据永久存入数据库
  2. 折叠功能只实现在 `ToolResultDisplay`（流式 nodeBlocks 通路），未实现在 `currentMessages`（历史消息通路）。两条通路完全独立——用户刷新页面后走历史通路，折叠消失
  3. `formatMessageContent`（历史消息展示）仍用 `\n → <br>`，与 `formatNodeContent` 的 Markdown 渲染不一致
  4. 验证只做了 `npm run build`（编译验证），没有覆盖：流式实时展示、流结束后的静默展示、页面刷新后的历史展示三个场景

- **修复**：
  1. 回退 `formatNodeContent` 中 `TextType.TEXT` 的 `markdownToHtml` 调用，恢复 `\n → <br>`
  2. `ToolResultDisplay` 内的折叠功能保留（仅在流式通路生效）
  3. 流结束后 nodeBlocks 容器从 `v-if="isStreaming"` 解耦为 `v-if="isStreaming || nodeBlocks.length > 0"`，保持结构化展示
  4. `onComplete` 中将 `selectSession` 替换为 `saveViewToState` + `preloadSessionLatestObservability`，避免 DB 加载的 HTML 覆盖结构化展示

- **教训**：[CHECKPOINT] 每次修改被多个消费方调用的函数（如 `formatNodeContent`）时，必须先用 grep 找到所有调用者并画出消费链路（调用者 → 函数 → 数据落点），逐链路确认影响。仅"编译通过"不能作为功能正确的判据，涉及 UI 渲染的改动必须覆盖三条场景路径：流式实时、流结束静默、页面刷新历史。

---

## 2026-07-17: 工具节点 DB 持久化将结构化 JSON 降级为扁平 HTML — 双路径渲染背离的根因

- **现象**：页面刷新后，工具结果（如 `metric.catalog.search` 的候选指标列表）退化为纯文本代码块，所有折叠功能、卡片布局、结构化展示全部消失。某些区域（指标候选、查询结果、语义命中、知识命中）即使在流式路径下也无法折叠，标题栏不显示`▾`箭头。
- **根因**：
  1. `saveAssistantNodeMessage` 对工具节点统一调用 `generateNodeHtml → formatNodeContent`，将 `AgentResponse[]`（含 JSON）转为 `<pre><code>` HTML 存入 `messageType: 'html'`。页面刷新后历史路径走 `formatMessageContent → v-html`，无法恢复结构化视图。
  2. `ToolResultDisplay` 中 4 个区域（`mt.candidates`、`mt.rows`、`sm.hits`、`dk.hits`）的 `<h4>` 缺少 `collapsible` 类、点击事件和 `v-show` 包装。
  3. `.tool-result-title` 不可点击，缺少整个卡片的折叠入口。
  4. 流式路径中 `ToolResultDisplay` 被多余的 `<div class="agent-response-block">` 包裹，与组件自身 `.tool-result-block` 形成双重边框/背景。
  5. `collapsedSections` 默认值散落在每个调用点，容易不一致。
- **修复**：
  1. **持久化改造**：`saveAssistantNodeMessage` 对 `nodeName.startsWith('tool:')` 的节点，将 `JSON.stringify(node)` 存入 `messageType: 'tool-result'`（代替 `'html'`）。历史路径模板新增 `v-else-if="messageType === 'tool-result'"` 块，用 `tryParseToolResult` 反序列化后传 `ToolResultDisplay`。
  2. **4 个缺失折叠**：`sm.hits`（语义命中）、`dk.hits`（知识命中）、`mt.candidates`（指标候选）、`mt.rows`（指标查询结果）各加 `collapsible` 类 + `@click="toggleSection"` + `<div v-show="!isCollapsed">`。
  3. **卡片折叠**：`.tool-result-title` 添加点击事件 `toggleCard` + `cardCollapsed` ref，标题文字左前加 `▾` 箭头 CSS 伪元素，内容区用 `v-show="!cardCollapsed"`。
  4. **双重包裹移除**：流式路径中包裹 `ToolResultDisplay` 的 `<div class="agent-response-block">` 去掉，组件自带完整样式。
  5. **默认值收敛**：`toggleSection` / `isCollapsed` 不再接受 `defaultCollapsed` 参数，改为查找集中定义的 `sectionDefaults` 常量表。
- **教训**：[AGENT RULE] 任何将组件渲染结果写入 DB 的代码，必须同时保证：写入的数据结构能被"读取路径"正确反序列化为相同的组件输入。不能乐观地认为 DB 写入和流式渲染走同一套格式化函数就够了——必须从"刷新页面后用户看到什么"开始倒推消费链路。

---

## 2026-07-20: 暗色模式下硬编码白色背景 + 继承白色文字 = 标题不可见

- **现象**：AgentDetail 页面的 h2 标题完全不可见，DOM 中文字存在但计算色为 `rgb(255,255,255)`，父容器 `el-header` 也是白色背景
- **根因**：
  1. `global.css` 的 `@media (prefers-color-scheme: dark)` 将 `--text-primary` 设为 `#ffffff`，body 继承该变量 → 暗色模式下默认文字为白色
  2. `AgentDetail.vue` 三处行内硬编码 `background-color: white`（el-header / el-aside / el-main），未使用 CSS 变量
  3. h2 没有显式 `color`，从 body 继承了白色文字 → 白字白底 = 不可见
- **修复**：三处行内 `white` 改为 CSS 类 `detail-panel`，显式设置 `color` 为深色，不与暗色模式变量联动
- **教训**：[CHECKPOINT] 任何行内硬编码背景色（如 `style="background-color: white"`）在暗色模式下都可能与从 body 继承的白色文字形成同色冲突。新增页面/组件时必须同时审查在 `prefers-color-scheme: dark` 下的颜色对比度。`global.css` 已有暗色变量定义但未被组件广泛采用，要么全部对齐变量做真暗色支持，要么显式设置 color 防止继承踩坑。

---

## 2026-07-20: 前端改动应通过浏览器自动化验证，不能只依赖 npm run build

- **现象**：多次前端改动（暗色模式标题、技能配置联动）仅通过 `npm run build` 编译和静态代码审查，但实际交付后用户环境与预判不符（如 AgentDetail 的标题在暗色模式下的颜色冲突，以及 AgentList 和 AgentDetail 问题的误判）
- **根因**：`npm run build` 只管编译/打包正确性，不验证运行时 DOM 状态、CSS 计算值、暗色模式下的颜色对比度、组件交互状态。仅凭源码推导容易漏判跨组件样式继承、全局变量覆盖、条件渲染等运行时问题
- **修复/方法**：macOS 下可利用 `osascript` 控制 Chrome，在目标页面执行 JS 获取计算样式和 DOM 状态：
  ```bash
  osascript -e '
  tell application "Google Chrome"
    execute active tab of front window javascript "
      JSON.stringify({
        h2_color: getComputedStyle(document.querySelector(\"h2\")).color,
        panels_bg: Array.from(document.querySelectorAll(\".detail-panel\"))
          .map(el => getComputedStyle(el).backgroundColor),
        radio_disabled: document.querySelector(\"input[value=metric-system]\").disabled
      })
    "
  end tell'
  ```
  验证清单：标题颜色/背景是否冲突、暗色模式下文字可见性、表单元素是否 disabled、CSS 变量是否实际解析
- **教训**：[CHECKPOINT] 任何前端 CSS/渲染改动，除了 `npm run build`，必须至少额外完成：① 切换到目标页面 URL（`set URL of active tab`）；② 通过 JS 读取目标元素的计算样式（`getComputedStyle`），验证 `color`/`backgroundColor` 对比度；③ 检查交互状态（`disabled`/`checked` 等）。静态代码审查不能替代运行时 DOM 验证。

## 模板

```markdown
## YYYY-MM-DD: 简短标题

- **现象**：
- **根因**：
- **修复**：
- **教训**：
```
