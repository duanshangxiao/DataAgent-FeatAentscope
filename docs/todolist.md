# 重构状态 & 待办事项

> 每次完成实质性改动后更新此文件。已完成事项归档不删除，当前待办只保留未完成项。

---

## 已完成

| 日期 | 事项 | 说明 |
|------|------|------|
| 2025-07 | 指标能力 (Metric Capability) | 插件式能力路由框架 + MetricCapabilityProvider，支持 OpenAPI 元数据同步 |
| 2026-07-15 | 指标检索架构重构 — PGVector 替代自研检索 | 删除 MetricCatalogIndex 自研检索，指标元数据导入 PGVector；解析器接口化支持多格式切换；LLM 驱动的工具选择替代应用层硬路由 |
| 2026-07-15 | 指标能力跨会话故障传染 + 时间解析错误 | 修复 HTTP 失败抛异常→返回 FALLBACK_TO_DB；移除 CB OPEN 时的 ABSTAIN 路由改为保持 MIXED；runtime instructions 注入当前日期；扩展时间推断支持近X月/周/上月/本月 |
| 2026-07-15 | 时间处理重构 — 从正则推导到 LLM 驱动 | 移除复杂时间正则(近X月/周/上月/本月)，改为 LLM 驱动：代码注入当前日期锚点 + 明确指令要求 LLM 解析相对时间为显式日期；仅保留"最近N天"兜底 |
| 2026-07-15 | 修复指标工具不被优先调用的问题 | runtime instructions 改为强制覆盖模式：remove 弱条件句，用"覆盖默认路由规则+必须严格遵守"的指令要求 LLM 先调 metric.catalog.search 再考虑 DB |
| 2026-07-17 | 工具结果展示优化 — 文本块→结构化渲染 | 后端 Hook 保留 JSON 结构(TextType.JSON)；前端新增 ToolResultDisplay 组件按 toolName 分派专用渲染(datasource/sql_guard/semantic_model/knowledge/metric/skill) |
| 2026-07-20 | Elasticsearch 集成 — 向量存储后端切换支持 | 新增 ES 8.18.0 docker-compose 配置(数据持久化、资源限制、磁盘水位线)；application.yml 切换 spring.ai.vectorstore.type 从 pgvector 到 elasticsearch，PGVector 配置注释保留；启用混合检索 enable-hybrid-search；修复 CapabilityRoutingServiceTest 编译错误(route 方法签名变更) |
| 2026-07-20 | 修复暗色模式下页面标题不可见 + 标题区域样式 | AgentDetail 页 el-header/el-aside/el-main 硬编码 background-color: white 在暗色模式下与继承的白色文字(id=color var(--text-primary))冲突，白底白字完全不可见；修复方案：三处行内 white 改为 CSS 类 detail-panel，显式设置 color 以对抗暗色模式文字继承；AgentList 页 .content-header 添加白色卡片样式(背景/边框/阴影)；index.html 去掉泛型 <title> 占位符 |
| 2026-07-20 | 技能配置与能力路由解耦优化 | 修复静默降级：用户选「指标查询」但技能未绑定时，后端不再静默回退 DB_ONLY，改为注入降级告知提示给 LLM；前端对话页「指标查询」radio 未绑技能时灰显 + tooltip 提示；精简 builtin-metric-system/SKILL.md 去除与 buildRuntimeInstructions 重复的行为指令 |
| 2026-07-20 | Prompt 架构重构 — 路径专用规则从基础 Prompt 分离 | commonagent.md 精简为通用规则(Human review directive)；DB 工具链 50+ 行规则移至新文件 prompts/db-path.md；CapabilityRoutingService.buildRuntimeInstructions() 按路由类型注入对应路径 Prompt：指标可用时注入指标规则+DB 回退规则，指标不可用时注入 DB_PATH_PROMPT；移除"覆盖默认路由规则"措辞（已无默认规则可覆盖） |
| 2026-07-20 | 指标召回修复 — 向量文档 Schema 系统性设计 + ES IK 分词 | Metric 文档 content 从 4 字段扩为 8 维度(description/tags/requestParameters/supportedGranularities 加入)；ES 安装 IK 分析器(content 字段 ik_max_word 索+ik_smart 检)；ES minScore 0.5→0.0 改由 RRF 统一排序；修复 Spring AI ES 维度自动推断失败(显式配置 dimensions:1024) |
| 2026-07-21 | 第一批架构整改（R-01～R-03、R-05、R-06） | 订正文档与当前运行链路；恢复 JUnit 5/Surefire 与 5% JaCoCo 门禁；同步 MySQL schema 并增加漂移测试；强制 `agentType=commonagent`；固定前端工具链并恢复 type-check/lint/build/浏览器验收。后端 35 项测试通过，前端真实页面零控制台错误和警告 |
| 2026-07-21 | 明文密钥代码收口（R-04 代码部分） | 移除仓库默认凭据，管理接口返回掩码，更新时保留未改密钥，连接测试日志不再输出完整配置；历史上已经暴露且仍有效的密钥仍需人工轮换后才能关闭 R-04 |

---

## 当前待办

### 2026-07-21 架构审查整改路线图

> 定位：当前项目按“可独立运行的功能模块”建设，不按企业级平台过度设计。现阶段不引入微服务、复杂治理体系或完整多租户权限模型。
>
> 排序规则：下表先按实施难度、再按建议执行顺序排列。难度不等于风险优先级；已暴露的密钥如仍有效，应立即轮换，不等待前置文档任务完成。

#### 第一批：简单（预计合计 1～2 人日）

| ID | 建议顺序 | 事项 | 主要工作 | 完成标准 | 状态 |
|---|---:|---|---|---|---|
| R-01 | 1 | 订正架构文档 | 将主链路由旧的 StateGraph 描述修正为 `CommonAgent(ReActAgent) + capability routing + dynamic tools + SSE`；同步 Elasticsearch/PGVector、数据表数量及中英文文档中的过期描述 | 文档与当前代码、配置一致；全文搜索无同类过期表述 | 已完成（2026-07-21） |
| R-02 | 2 | 修复后端测试“假绿” | 在 POM 固定支持 JUnit 5 的 Surefire 3.x；确保 Maven 3 与 CI 都实际执行测试；按“保留 DB fallback”的当前语义更新过期测试；先设置真实可达的覆盖率门槛 | 常规 `mvn test` 的测试数大于 0；现有测试通过；CI 与本地结果一致 | 已完成（2026-07-21；35 项测试） |
| R-03 | 3 | 同步 SQL 基线 | 对齐主/test schema；涉及 H2 的结构同步更新 H2 基线；补充关键表、字段、索引差异检查 | MySQL 主/test schema 关键结构无漂移；相关初始化测试通过；旧库手工对齐要求有说明 | 已完成（2026-07-21） |
| R-04 | 4 | 收口明文密钥暴露 | 移除配置文件中的真实默认口令和注释凭据；连接测试失败日志不得打印完整配置；接口返回对 API key、代理密码等敏感字段脱敏 | 仓库扫描无有效凭据；日志和普通查询接口不返回明文密钥；仍有效的历史密钥已人工轮换 | 代码完成（2026-07-21）；待人工轮换历史密钥 |
| R-05 | 6 | 强制 `commonagent` 收敛 | 写入和运行时统一归一化 `agentType=commonagent`，仅保留必要的外部兼容 | 任意空值或旧值进入核心路径后均为 `commonagent`；有回归测试 | 已完成（2026-07-21） |
| R-06 | 7 | 恢复前端质量门禁 | 固定兼容的 Node 版本或升级 `vue-tsc`；清除当前 lint error；将大规模格式问题作为独立机械任务处理 | `type-check`、`lint:check`、`build` 均通过，且未夹带无关格式化 | 已完成（2026-07-21；含浏览器验收） |

#### 第二批：中等（预计每项 2～5 人日）

| ID | 建议顺序 | 事项 | 主要工作 | 完成标准 | 状态 |
|---|---:|---|---|---|---|
| R-07 | 5 | 统一修复存储型 XSS | 纯文本统一转义；Markdown 经可信 sanitizer；不信任历史消息的 `html` 类型；节点名、错误、数据库结果等所有 HTML 拼接入口统一处理；明确需要保留 HTML 时的沙箱或下载策略 | 用户、数据库、模型和历史消息中的恶意标签均不能执行；覆盖实时流、流结束和刷新历史三条路径；有针对性测试 | 待办 |
| R-08 | 8 | 明确模块访问边界 | 默认绑定本机或可信内网；收窄 CORS；文档明确不可直接暴露公网/共享网络；需要远程部署时可先增加简单部署令牌 | 默认部署不存在任意来源跨域访问；部署说明清楚列出信任边界和启用远程访问的前提 | 待办 |
| R-09 | 后续 | SSE GET 改为 POST 流 | 避免 query、human feedback 等内容进入 URL、访问日志或受 URL 长度限制 | 前后端改用 POST 流式请求；停止/取消、错误处理和兼容路径验证通过 | 待办 |
| R-10 | 后续 | 敏感配置加密存储 | 引入集中式 `SecretCodec` 或等价抽象；数据库/模型/代理密码加密落库；查询接口只返回掩码；制定旧数据手工迁移方式 | 新数据不明文落库；密钥可正常使用但不可通过普通接口读取；旧库升级说明完整 | 待办 |

#### 第三批：困难（预计合计 1～2 周）

| ID | 建议顺序 | 事项 | 主要工作 | 完成标准 | 状态 |
|---|---:|---|---|---|---|
| R-11 | 9 | 后端拥有一次对话的持久化事务 | 将“用户消息 → 运行时执行 → 助手事件/消息落库”收归后端；以 turn/runtime request 标识实现幂等；前端只负责发起请求和渲染事件 | 正常完成、保存失败、取消、断连和刷新场景均不产生重复消息或不可解释的半截历史；memory 与可见历史语义一致 | 待办 |
| R-12 | 10 | 拆分超大前端组件 | 将 `AgentRun.vue` 的会话、流式、格式化、持久化等职责拆为 composable/子组件；按工具类型继续拆分结果展示 | 关键组件职责清晰；实时与历史渲染行为不变；类型检查、构建和浏览器回归通过 | 待办 |
| R-13 | 10 | 拆分超大后端服务 | 在同一 Maven 模块内拆分数据源探索、SQL 校验/解释、运行时编排等职责，不改变部署形态 | 服务边界和依赖方向清晰；既有接口与行为保持兼容；相关测试通过 | 待办 |

#### 第四批：高难度、暂不急做

| ID | 触发条件 | 事项 | 决策原则 | 状态 |
|---|---|---|---|---|
| R-14 | 阻塞调用已造成可观测的吞吐、线程或维护问题 | 统一 MVC/WebFlux 技术模型 | 现有 MyBatis/JDBC/AgentScope 以阻塞调用为主，届时优先评估 MVC + SSE；若保留 WebFlux，则必须系统性隔离阻塞调用 | 观察项 |
| R-15 | 项目开始面向多用户、共享网络或公网独立部署 | 完整认证授权与多用户隔离 | 再引入用户身份、会话/Agent 所有权、数据权限和审计；当前阶段不提前建设企业级权限平台 | 观察项 |

#### 推荐落地顺序

`R-04（人工轮换历史密钥）→ R-07 → R-08 → R-11 → R-12/R-13`

- `R-09`、`R-10` 可在第二批有独立开发窗口时穿插，不阻塞核心整改链路。
- `R-14`、`R-15` 只在触发条件出现后立项，避免将功能模块过度企业化。
- 每完成一项，将状态改为“已完成”，记录完成日期与验证结果，并将实质性结果归档到“已完成”表。

---

## 长期关注

- 保证所有 Agent 行为收敛到 `commonagent/system`（不扩展 agentType / scene 维度）
- SQL 基线 (`schema.sql` / `data.sql`) 与真实数据库保持一致
- `todolist.md` 作为重构状态的单一事实源
