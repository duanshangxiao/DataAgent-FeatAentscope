# Vibe Coding 文档体系说明

> **目标读者**：项目维护者。
> **用途**：帮助维护者理解当前 vibe coding 文档的布局、每个文件的作用、以及新增文件时需要做什么。
> **重要**：每次新增、删除、重命名 vibe coding 相关文件时，必须同步更新本文档的索引表。

---

## 什么是 Vibe Coding 文档

Vibe Coding 文档是指那些**指导 AI 编码 agent 如何在本项目中工作**的文件。包括：
- Agent 行为约束（什么能做、什么不能做）
- 项目架构知识（包结构、技术栈、调用链）
- 任务进度追踪（待办、已完成）
- 历史教训（踩过的坑）
- AI 运行时提示词（LLM 工具路由规则）

这些文件的核心目标是：**让一个新的 AI 会话能尽可能快、尽可能准确地理解当前项目的上下文，保证稳定、一致的编码输出。**

---

## 加载机制

本项目使用两种加载方式：

| 方式 | 配置位置 | 说明 |
|------|----------|------|
| **自动注入** | `opencode.json` → `instructions` 字段 | 每次会话启动时，指定文件的内容直接注入 AI 上下文。适合放核心约束和架构文档。 |
| **按需引用** | `AGENT.md` 中显式写路径 | Agent 需要在推理过程中自行读取。适合大文件或场景化文档。 |

当前 `opencode.json` 自动注入的文件：
- `AGENT.md`
- `docs/ARCHITECTURE.md`
- `docs/todolist.md`
- `docs/LESSONS.md`

其余文件由 `AGENT.md` 中的交叉引用引导 Agent 按需读取。

---

## 文档索引

### 核心控制层（会话启动即加载）

| 文件 | 作用 | 受众 | 必须更新时机 |
|------|------|------|-------------|
| `AGENT.md` | 仓库级 AI 编码约束：产品约束、修改原则、代码风格、禁止事项、验证要求 | AI agent | 产品约束变化、代码风格调整、新增禁止项 |
| `opencode.json` | 控制哪些文档自动注入 AI 上下文 | OpenCode 框架 | 新增核心文档需自动加载时 |
| `docs/ARCHITECTURE.md` | 技术栈、包结构树、核心设计（StateGraph 流水线）、关键配置 | AI agent + 新开发者 | 包结构调整、新增模块、架构变更 |
| `docs/todolist.md` | 重构状态单一事实源：已完成/待办/长期关注 | AI agent + 维护者 | 每次完成实质性改动后（见 AGENT.md 第5节） |
| `docs/LESSONS.md` | 历史教训：bug根因、踩坑记录、修复方案 | AI agent + 维护者 | 每次发现/修复非直觉的 bug 后 |

### 编码规范层（按需引用）

| 文件 | 作用 | 受众 | 必须更新时机 |
|------|------|------|-------------|
| `docs/DEVELOPER_GUIDE.md` | 开发环境搭建 + Java/TS 详细编码规范 | 人类开发者 + AI agent | 开发环境变化、编码规范调整 |
| `CONTRIBUTING-zh.md` | 代码贡献流程、Spring 代码格式、commit 规范 | 外部贡献者 + AI agent | 贡献流程变化 |
| `CONTRIBUTING-en.md` | 同上，英文版 | 外部贡献者 | 与 -zh.md 同步更新 |
| `data-agent-frontend/README-CODE-STYLE.md` | 前端 Prettier/ESLint/vue-tsc 规则 | 前端开发者 + AI agent | 前端工具链配置变更 |

### Agent 运行时提示词（影响 LLM 推理行为）

| 文件 | 作用 | 受众 | 必须更新时机 |
|------|------|------|-------------|
| `data-agent-management/src/main/resources/prompts/commonagent.md` | NL2SQL Agent 核心系统提示词：工具路由优先级、PREVIEW_ROWS/DATA_PROFILE/ sql_guard 约束 | LLM（运行时） | 工具路由规则变化、新增工具、约束调整 |
| `agent-skills/builtin-current-time/SKILL.md` | 时间查询 skill 的行为约束和边缘情况 | LLM（运行时） | 时间工具行为变化 |
| `agent-skills/builtin-domain-business-knowledge/SKILL.md` | 领域知识检索 skill 的行为约束 | LLM（运行时） | 知识检索策略变化 |
| `agent-skills/builtin-metric-system/SKILL.md` | 指标系统 skill 的行为约束和错误处理 | LLM（运行时） | 指标工具行为变化 |

### 参考知识层（AI agent 查阅用）

| 文件 | 作用 | 受众 | 必须更新时机 |
|------|------|------|-------------|
| `docs/KNOWLEDGE_USAGE.md` | 如何配置语义模型/业务知识来优化 AI 行为 | 系统管理员 + AI agent | 知识配置机制变化 |
| `docs/ELASTICSEARCH.md` | ES 集成说明：切换配置、容器管理、数据查看、混合检索、故障排查 | AI agent + 维护者 | ES 配置或容器参数变更 |
| `docs/duan/data-agent-metric-capability-implementation-plan.md` | 指标能力实现规格书（Phase 1-5、禁止项、验收标准） | AI agent（实现参考） | 指标能力需求变更 |
| `docs/duan/data-agent-metric-capability-fix-prompt.md` | 指标能力 bug 修复任务书 | AI agent（修复参考） | 修复完成后归档到 LESSONS.md |

### 用户向文档（非 vibe coding，仅供参考）

| 文件 | 作用 |
|------|------|
| `README.md` / `README-en.md` | 项目概览 |
| `docs/QUICK_START.md` | 快速开始 |
| `docs/ADVANCED_FEATURES.md` | 高级特性 |
| `SECURITY.md` | 安全漏洞报告 |

---

## 维护清单

### 新增 Vibe Coding 文档时

1. 确定该文档的加载方式：
   - 如果是**核心约束/架构知识** → 加入 `opencode.json` 的 `instructions` 数组
   - 如果是**场景化/大文件** → 在 `AGENT.md` 中加交叉引用
2. 在本文档的索引表中新增一行
3. 如果该文档是某些操作的产物（如 `LESSONS.md`），在 `AGENT.md` 的相关章节加"更新 xxx.md"的要求

### 删除/重命名时

1. 同步更新 `opencode.json`（如果之前在其中）
2. 同步更新 `AGENT.md` 中的引用
3. 同步更新本文档的索引表

### 每次 AGENT.md 引用位置变化时

检查本文档的索引表是否仍准确。

---

## 文档维护节奏

| 文档 | 更新频率 | 负责人 |
|------|----------|--------|
| `AGENT.md` | 产品约束变化时 | 维护者 |
| `docs/ARCHITECTURE.md` | 架构变更时 | 维护者 |
| `docs/todolist.md` | 每次实质性改动后 | 当前迭代的 AI agent |
| `docs/LESSONS.md` | 每次修复非直觉 bug 后 | 发现者 |
| `docs/VIBE_CODING.md`（本文件） | 新增/删除 vibe coding 文档时 | 操作者 |

---

> **最后更新**：2026-07-14
