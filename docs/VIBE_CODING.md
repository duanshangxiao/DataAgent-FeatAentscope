# AI 辅助开发文档体系

> 目标读者：项目维护者和参与开发的 AI 编码工具。
>
> 目标：用一个稳定规则入口连接架构、状态和历史经验，避免把所有文档无差别注入每次会话。

## 1. 核心原则

- `AGENTS.md` 是仓库级持久规则的唯一事实源。
- 架构、待办和历史事故按任务需要读取，不作为每次会话的固定上下文。
- 规则描述“以后都要遵守什么”，经验文档解释“过去为什么出错”，二者不混写。
- 能由测试、lint、类型或脚本强制的约束，优先工程化，不长期依赖文字提醒。
- 一次性任务摘要、调查记录和临时方案不升级为仓库规则。

## 2. 加载机制

| 工具/方式 | 入口 | 行为 |
|---|---|---|
| Codex | 根目录 `AGENTS.md` | 启动任务时读取仓库持久规则 |
| OpenCode 兼容 | `opencode.json` | 仅注入同一份 `AGENTS.md`，不维护第二套规则 |
| 开发者/AI 按需查阅 | `AGENTS.md` 中的文档链接 | 根据改动范围读取架构、规范、状态和历史经验 |

不要把 `docs/ARCHITECTURE.md`、`docs/todolist.md` 或 `docs/LESSONS.md` 全文加入自动注入列表。它们体积较大、更新频率和用途不同，全文注入容易放大过期信息和上下文噪声。

## 3. 文档分层

### 3.1 持久规则层

| 文件 | 作用 | 更新时机 |
|---|---|---|
| `AGENTS.md` | 产品不变量、修改边界、验证要求、完成检查 | 稳定约束或默认工作方式发生变化时 |
| `opencode.json` | OpenCode 兼容入口 | 兼容策略或规则入口变化时 |

`AGENTS.md` 应保持简洁、可执行。不要加入完整事故复盘、临时任务状态、个人偏好或工具输出。

### 3.2 项目知识层

| 文件 | 作用 | 更新时机 |
|---|---|---|
| `docs/ARCHITECTURE.md` | 技术栈、模块结构、核心调用链和架构边界 | 架构事实变化时 |
| `docs/CONFIGURATION.md` | 环境变量、配置默认值和密钥约束 | 配置入口或默认值变化时 |
| `docs/DEVELOPER_GUIDE.md` | 开发、测试、验证和编码规范 | 工具链或开发流程变化时 |
| `docs/DEPLOYMENT.md` | 依赖、部署边界和运行检查 | 部署形态或外部依赖变化时 |
| `docs/UPGRADE.md` | SQL 基线和旧库手工升级 | 数据库结构或升级步骤变化时 |
| `docs/API_AND_SSE.md` | REST 入口、SSE 事件和标识语义 | API 或流式协议变化时 |
| `CONTRIBUTING-zh.md` | 贡献、格式和提交约定 | 贡献流程变化时 |
| `data-agent-frontend/README-CODE-STYLE.md` | 前端格式、lint、类型与无用代码检查 | 前端工具链变化时 |
| `docs/KNOWLEDGE_USAGE.md` | 语义模型和业务知识配置 | 知识配置机制变化时 |
| `docs/ELASTICSEARCH.md` | Elasticsearch 配置与排障 | ES 集成变化时 |
| `docs/METRIC_CATALOG_RETRIEVAL.md` | 指标/API拆分、公共检索、本地修正与上下架实施方案 | 指标目录或检索机制变化时 |
| `docs/METRIC_CATALOG_INTEGRATION_SPEC.md` | 第三方指标目录通用规范与 data-metrics 供应方 API Profile | 第三方目录协议或供应方契约变化时 |
| `docs/METRIC_CATALOG_INTEGRATION_PLAN.md` | 标准目录解析、metricKey 工具链和 HTTP 响应改造计划 | 指标标准化接入实施范围或状态变化时 |
| `docs/METRIC_CATALOG_OPERATIONS.md` | 第三方指标目录切换、配置、验证、回滚和运维排障 | 指标目录部署配置或运行边界变化时 |

### 3.3 状态与经验层

| 文件 | 作用 | 更新时机 |
|---|---|---|
| `docs/todolist.md` | 当前路线图、已完成事项和长期关注 | 路线图或完成状态实际变化时 |
| `docs/LESSONS.md` | 非直觉故障的现象、根因、修复和教训 | 出现高价值、容易复发的经验时 |
| `docs/duan/` | 早期 OpenCode 修复问题的调查、任务书和实施记录 | 历史只读；当前事实提炼到正式文档 |

`docs/LESSONS.md` 是历史案例库，不是第二份规则文件。仍然有效的结论应提炼成 `AGENTS.md` 中的一条短规则，事故细节继续留在原处。

`docs/duan/` 不纳入正式文档导航，不要求随当前代码持续更新。遇到冲突时以代码、测试、配置和正式文档为准。

项目后续只维护中文说明，不再维护人工同步的英文镜像。若未来重新提供英文文档，应通过明确的生成或发布流程避免双份内容漂移。

### 3.4 Agent 运行时层

以下文件影响产品内 LLM 的运行行为，不等同于开发工具的仓库指令：

| 文件 | 作用 |
|---|---|
| `data-agent-management/src/main/resources/prompts/commonagent.md` | 通用 Agent 系统提示词 |
| `data-agent-management/src/main/resources/prompts/db-path.md` | DB 路径运行时规则 |
| `agent-skills/builtin-current-time/SKILL.md` | 当前时间技能 |
| `agent-skills/builtin-domain-business-knowledge/SKILL.md` | 领域知识检索技能 |
| `agent-skills/builtin-metric-system/SKILL.md` | 指标系统技能 |

修改这些文件时需要验证产品内 Agent 行为，但不要把其完整内容复制进 `AGENTS.md`。

## 4. 经验进入规则的流程

发现问题后按以下顺序处理：

1. 修复根因，并补充与风险相称的测试或验证。
2. 如果问题非直觉、代价较高或容易复发，在 `docs/LESSONS.md` 记录完整案例。
3. 如果能自动拦截，增加测试、lint、类型、脚本或 CI 检查。
4. 如果仍需要跨任务提醒，将结论压缩成一条可执行规则写入 `AGENTS.md`。
5. 不把具体人名、会话过程、临时命令输出或已失效方案写入持久规则。

## 5. 维护检查

新增、删除或重命名 AI 协作文档时：

1. 判断它属于持久规则、项目知识、状态经验还是运行时提示词。
2. 更新本文件对应索引。
3. 如果改变规则入口，同步更新 `AGENTS.md` 和 `opencode.json`。
4. 搜索旧文件名和旧术语，确保没有失效引用。
5. 检查新增内容是否与当前代码、测试和配置一致。

建议定期清理：

- 已被自动化检查取代的文字规则。
- 已失效但仍被描述为当前行为的历史经验。
- 只服务于一次任务的上下文摘要。
- 重复存在于多个入口的相同规则。

---

最后更新：2026-07-24（增加第三方指标目录运维手册）
