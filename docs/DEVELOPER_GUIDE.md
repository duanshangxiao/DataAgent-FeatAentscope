# 开发者指南

本文面向修改 DataAgent 代码的开发者，说明工程结构、开发流程和验证要求。首次运行请先完成[快速开始](QUICK_START.md)。

## 1. 工程结构

```text
DataAgent/
├── data-agent-management/   # Java 17 / Spring Boot 后端
├── data-agent-frontend/     # Vue 3 / TypeScript 前端
├── agent-skills/            # 内置运行时技能
├── docker-file/             # 本地 Docker 示例
├── docs/                    # 当前项目说明与内部记录
├── AGENTS.md                # AI 与开发者的稳定协作规则
└── pom.xml                  # Maven 聚合入口
```

后端没有拆成多个部署服务。Controller、Service、Mapper、AgentScope 适配、capability 和工具提供者都位于 `data-agent-management`。

## 2. 开发环境

- JDK 17
- Node.js 18+
- MySQL 8.0 推荐
- Elasticsearch 8.18.0
- Git
- Docker，可选但推荐用于本地依赖和容器化测试

后端使用仓库根目录 `./mvnw`，前端使用锁文件和 `npm ci`。环境变量见[配置参考](CONFIGURATION.md)。

## 3. 当前核心链路

对话主链路由 `AiAgentRuntimeServiceImpl` 编排：

1. `DataAgentController` 接收 SSE 请求并验证 `agentId/threadId`。
2. `AgentScopeMemoryFactory` 按 `threadId` 载入原生 memory。
3. `CapabilityRoutingService` 选择数据库或指标混合路径。
4. `AgentScopeToolkitFactory` 与 Agent 级工具目录组装工具。
5. `CommonAgent` 创建 AgentScope `ReActAgent` 执行。
6. Hook 把文本、工具调用和结果转换为 SSE。
7. 完成后持久化 memory、回答解释和必要的会话数据。

历史 StateGraph 节点流水线不是当前主链路。完整关系见[架构说明](ARCHITECTURE.md)。

## 4. 修改前定位完整调用链

跨层需求至少检查：

- Controller 与请求 DTO。
- Service 和运行时编排。
- Mapper、SQL 与真实数据落点。
- 前端 service、组件参数、保存和编辑回填。
- SSE 实时展示与刷新后的历史展示。

修改消息类型时，同时检查 UI 可见性、memory eligibility、`ChatMessageMapper`、`ChatMessageService` 和前端渲染。修改停止/取消时，同时验证断开 SSE、后端运行停止或副作用抑制、memory 不发生脏回写。

## 5. 后端开发与验证

### 编译

Java 改动至少执行：

```bash
./mvnw -pl data-agent-management -am -DskipTests compile
```

### 测试

运行后端测试：

```bash
./mvnw -pl data-agent-management -am test
```

可使用 `-Dtest=ClassName#methodName` 运行针对性测试。修改业务行为应补充最小回归测试；数据库行为不能只依赖 H2 判断，MySQL schema 或兼容性变化必须在真实 MySQL 或 Testcontainers 中验证。

### Spring 容器启动

修改 Bean 构造器、注入关系、配置属性、`@Bean`、`@Scheduled`、`@Async` 或事件监听后，编译通过仍不够。需要启动应用并确认：

- 出现 `Started DataAgentApplication`。
- 没有循环依赖和 Bean 初始化错误。
- 外部数据库、ES 或模型服务失败与 Spring 容器自身失败被正确区分。

## 6. 前端开发与验证

安装依赖：

```bash
cd data-agent-frontend
npm ci
```

逻辑改动按范围执行：

```bash
npm run type-check
npm run lint:check
npm run build
```

提交前还应根据改动范围执行：

```bash
npm run format:check
npm run unused
```

不要用 `npm run lint` 或 `npm run format` 顺手改写大量无关文件。

CSS、条件渲染、流式展示和交互状态不能只靠构建验证。至少在真实页面检查：

- 实时流式阶段。
- 流结束后的静默状态。
- 刷新后的历史展示。
- 明暗主题下的文字和背景对比度。
- 表单的 disabled、checked、保存回填和 reload 行为。

前端详细风格见 `data-agent-frontend/README-CODE-STYLE.md`。

## 7. 数据库变更

应用不会运行自动 migration。MySQL schema 变更至少同步：

- `data-agent-management/src/main/resources/sql/schema.sql`
- `data-agent-management/src/test/resources/sql/schema.sql`

涉及 H2 测试时再同步 `src/main/resources/sql/h2/` 对应文件。旧库需要执行的增量 DDL 必须写入[升级说明](UPGRADE.md)或对应专题升级章节。

未经明确授权，不直接修改共享或生产数据库。

## 8. Prompt、Agent 和技能

- 业务侧默认只保留 `agentType=commonagent`。
- 系统提示词默认只保留 `promptType=system`。
- 不把 `scene` 恢复为 Prompt 配置维度。
- 外部兼容值进入核心路径后应收敛为 `commonagent/system`。

运行时系统提示词位于 `data-agent-management/src/main/resources/prompts/`，内置技能位于 `agent-skills/`。修改后需要验证模型实际工具选择和输出，不只检查 Markdown 格式。

## 9. API 与流式修改

普通 REST 字段以 `/v3/api-docs` 为准。SSE 的事件、标识和取消语义见 [API 与 SSE](API_AND_SSE.md)。

修改结构化工具结果时，数据库应保存可反序列化的原始结构，不应先降级为 HTML。任何多方消费的格式化函数都要列出“调用者 → 函数 → 数据落点”，分别判断实时和历史影响。

## 10. 文档维护

- README 只保留项目定位、快速入口和导航。
- `CONFIGURATION.md` 是环境变量和配置的唯一完整入口。
- `ARCHITECTURE.md` 只记录当前架构事实。
- `todolist.md` 记录当前路线图，不作为发布说明。
- `LESSONS.md` 保存高价值历史根因，稳定规则再提炼到 `AGENTS.md`。
- `docs/duan/` 是早期 OpenCode 修复记录，不作为当前事实来源。
- 项目后续只维护中文说明，不再维护英文镜像。

文档与实现冲突时，以当前代码、测试和配置为准，并在当前任务范围修正文档漂移。

## 11. 完成前检查

- 改动范围是否最小且没有覆盖用户已有修改。
- `commonagent/system` 产品约束是否保持。
- Controller、Service、Mapper、SQL、前端与持久化是否闭环。
- 实时流、取消、memory 和历史展示是否一致。
- 验证是否与改动风险相称。
- 是否留下临时脚本、调试输出、生成物或无关格式化。
- 配置、部署、升级或 API 事实变化时，相关正式文档是否同步。
