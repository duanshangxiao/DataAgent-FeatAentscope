# DataAgent 仓库协作规范

本文件是本仓库面向 Codex、其他 AI 编码工具和开发者的唯一持久规则入口。只在这里保留稳定、可执行、跨任务有效的约束；架构说明、任务状态和历史事故按需读取，不全文复制到会话上下文。

## 1. 仓库与知识入口

- 后端：`data-agent-management`（Java 17、Spring Boot、WebFlux、MyBatis）
- 前端：`data-agent-frontend`（Vue 3、TypeScript、Vite、Element Plus）
- 架构：`docs/ARCHITECTURE.md`
- 当前状态：`docs/todolist.md`
- 历史事故与根因：`docs/LESSONS.md`
- 开发规范：`docs/DEVELOPER_GUIDE.md`、`CONTRIBUTING-zh.md`
- 前端规范：`data-agent-frontend/README-CODE-STYLE.md`
- AI 协作文档索引：`docs/VIBE_CODING.md`
- SQL 基线：`data-agent-management/src/main/resources/sql/`

开始任务时先按改动范围读取相关资料，不要默认加载全部历史文档。文档与实现冲突时，以当前代码、测试和配置为准，并在本次任务范围内修正文档漂移。

## 2. 不可破坏的产品约束

### 2.1 Agent 与 Prompt

- 业务侧默认只保留 `agentType=commonagent`。
- 系统提示词默认只保留 `promptType=system`。
- 不把 `scene` 重新扩展为 Prompt 配置维度。
- 兼容接口可以保留，但落库和运行时语义必须收敛到 `commonagent/system`。
- 不新增多 `agentType` 模板体系，除非需求明确要求改变现有产品设计。

### 2.2 会话与 Memory

- 前端流式请求的 `threadId` 默认使用当前 `sessionId`。
- `memory-text` 只进入 memory，不直接返回会话消息列表。
- 新增或修改消息类型时，同时检查 UI 可见性、memory eligibility、`ChatMessageMapper`、`ChatMessageService` 和前端渲染。
- 修改 stop/cancel 时，同时检查 SSE 停止、后端执行停止或副作用抑制，以及 `memory-text` 是否可能脏回写。

### 2.3 数据库

- 真实 MySQL 是数据库行为的主要判据；H2 只作为测试基线，不能代替 MySQL 兼容性判断。
- 不引入启动期自动 migration runner，也不要让应用启动时偷偷修改旧库结构。
- 表结构变更直接维护 SQL 基线。修改 MySQL schema 时至少同步：
  - `data-agent-management/src/main/resources/sql/schema.sql`
  - `data-agent-management/src/test/resources/sql/schema.sql`
- 涉及 H2 测试或初始化数据时，再同步 `src/main/resources/sql/h2/` 下的对应文件。
- 旧库需要手工对齐时，在交付说明或升级文档中明确说明。
- 未经用户明确授权，不直接修改共享或生产数据库。

## 3. 修改工作流

### 3.1 先定位完整链路

- 修改前搜索所有定义、调用者、持久化落点和前端消费点。
- 跨层需求应一次闭环检查 Controller、Service、Mapper/SQL、DTO、前端请求与展示。
- 被多个调用方消费的函数，先列出“调用者 → 函数 → 数据落点”，逐条判断影响。
- 保持最小必要改动，不做与需求无关的重构，不覆盖用户已有改动。

### 3.2 后端约定

- 沿用现有 Java、Spring Boot、WebFlux、MyBatis 风格。
- 公共默认值集中定义，避免散落魔法字符串。
- 兼容逻辑应“对外兼容、对内收敛”，不要把旧语义继续传进核心路径。
- 修改 Bean 构造器、注入方向、配置属性、`@Bean`、`@Scheduled`、`@Async` 或事件监听时，必须额外检查循环依赖和应用启动。
- 运行时问题优先根据现有日志和异常堆栈定位到具体代码；补日志时不得输出密钥、连接串或用户敏感数据。

### 3.3 前端约定

- 沿用 Vue 3 + TypeScript + Element Plus 的现有写法，不为单次需求强推新抽象。
- Vue 组件使用 PascalCase，变量和函数使用 camelCase；接口类型优先使用 `interface`，避免新增无约束的 `any`。
- 修改请求参数时同步检查 service 层、组件调用、保存参数、编辑回填和后端 DTO。
- 新增页面或组件时检查路由、菜单、权限入口和完成后的 reload 行为。
- UI 不能只做到“看起来可配置”；必须确认数据真实落库并能正确回填。

### 3.4 流式、历史与持久化双路径

- 修改 `formatNodeContent`、`formatMessageContent`、`generateNodeHtml` 等多方消费函数时，同时审查流式展示和数据库历史展示。
- 结构化组件数据写入数据库时保存可反序列化的原始结构，不先降级成不可恢复的 HTML。
- 从“刷新页面后用户看到什么”倒推持久化格式和组件 props，而不是只验证实时流式路径。
- UI 渲染改动至少覆盖：流式实时、流结束静默、刷新后的历史展示。
- 样式改动检查明暗主题下的文字/背景对比度和表单交互状态；构建成功不能替代运行时验证。

## 4. 验证要求

验证应与改动风险成比例。无法执行某项验证时，在交付结果中说明未执行项和原因。

### 4.1 后端

- Java 改动至少执行：

  ```bash
  mvn -pl data-agent-management -am -DskipTests compile
  ```

- 修改业务行为时运行相关测试；新增回归风险时补充针对性测试。
- 涉及 Spring Bean、配置或代理关系时，编译后运行应用启动验证，确认出现 `Started DataAgentApplication`，且没有容器循环依赖或初始化错误。
- 外部数据库、Elasticsearch、模型服务不可用时，区分外部依赖失败与 Spring 容器自身失败，不把两者混为一谈。

### 4.2 前端

- TypeScript 或 Vue 逻辑改动按范围执行：

  ```bash
  cd data-agent-frontend
  npm run type-check
  npm run lint:check
  npm run build
  ```

- CSS、条件渲染、交互状态或流式展示改动，增加浏览器运行时验证；优先使用可复现的 mock、自动化检查或明确的人工步骤。
- 不使用会自动改写大量无关文件的 `npm run lint` 或 `npm run format`，除非本次任务就是格式化或已确认改写范围。

### 4.3 文档与配置

- 纯文档或 AI 协作配置迁移不要求编译业务工程，但必须检查 JSON/Markdown、路径引用、重复入口和 `git diff`。

## 5. 文档与经验维护

- `AGENTS.md` 只写稳定规则，不记录一次性任务上下文、临时方案或长篇事故过程。
- `docs/ARCHITECTURE.md` 只在架构、模块、关键调用链或技术栈发生变化时更新。
- `docs/todolist.md` 只在任务确实改变路线图、完成状态或长期关注项时更新；小修复不强制写工作日志。
- 非直觉、容易复发或代价较高的问题写入 `docs/LESSONS.md`，格式包含现象、根因、修复和可复用教训。
- 同类问题重复出现时，优先用测试、lint、脚本或类型约束进行工程化拦截；无法自动拦截的，再提炼为本文件中的简短检查项。
- 新增、删除或重命名 AI 协作文档时，同步更新 `docs/VIBE_CODING.md`。
- 任务专用上下文摘要放在任务文档或归档目录，不作为全仓库持久规则。

## 6. 完成前检查

- 改动是否符合 `commonagent/system` 和现有 Prompt 语义。
- 跨层调用链、SQL 基线、流式/历史双路径是否同步闭环。
- 是否运行了与风险相称的编译、测试、构建和运行时验证。
- 是否留下临时脚本、调试输出、生成物、无用 import 或无关格式化改动。
- 文档只在事实确实变化时更新，历史经验没有被误当成当前规则。
- 最终说明包含：改了什么、为什么这样改、验证结果，以及仍存在的风险或未执行项。
