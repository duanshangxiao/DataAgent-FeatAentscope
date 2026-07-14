# DataAgent 仓库协作规范

本文件定义仓库内 AI agent / 开发者的默认工作约束。目标是让改动稳定、可回归、符合当前项目已经收敛出的实现习惯。

## 1. 仓库结构

- 后端主工程：`data-agent-management`
- 前端主工程：`data-agent-frontend`
- 会话自动注入配置：`opencode.json`（控制哪些文档在会话启动时加载）
- 文档与重构状态：`docs/todolist.md`
- 项目架构说明：`docs/ARCHITECTURE.md`（技术栈、包结构、核心设计）
- 历史教训：`docs/LESSONS.md`（bug 根因、踩坑记录）
- Vibe Coding 文档体系说明：`docs/VIBE_CODING.md`（给维护者看的索引）
- SQL 基线：`data-agent-management/src/main/resources/sql/`

默认先看后端，再看前端，最后同步文档。

## 2. 当前产品约束

### 2.1 Agent / Prompt 约束

- 业务侧只保留一个默认 `agentType=commonagent`
- 系统提示词只保留一个默认 `promptType=system`
- 不要再把 `scene` 当作 Prompt 配置维度扩展
- 兼容接口可以保留，但落库和运行时语义必须统一收敛到 `commonagent/system`

### 2.2 会话 / Memory 约束

- 前端流式请求的 `threadId` 默认直接使用当前 `sessionId`
- `memory-text` 只进入 memory，不直接返回给会话消息列表
- 新增消息类型时，必须同时审查：
  - 是否应在 UI 可见
  - 是否应进入 memory
  - `ChatMessageMapper` / `ChatMessageService` / 前端渲染是否需要同步
- 任何 stop / cancel 改动，必须同时检查：
  - SSE 停止
  - 后端执行停止或最少做到副作用抑制
  - `memory-text` 不被脏回写

### 2.3 数据库约束

- 默认验证环境是真实 MySQL，不以 H2 作为本轮主判据
- 不保留启动期 migration runner
- 结构变更直接落到 SQL 基线文件
- 改 MySQL 基线时，至少同步：
  - `src/main/resources/sql/schema.sql`
  - `src/test/resources/sql/schema.sql`
- 如果是旧库兼容问题，要在文档里明确"需要手工对齐"，不要偷偷加启动时自动修库

## 3. 修改原则

### 3.1 先查现状再动手

- 先定位完整调用链，再修改
- 优先查：
  - Controller
  - Service
  - Mapper / SQL
  - 前端调用点
  - `docs/todolist.md`
- 不要只改表层接口而不看运行时真实落点

### 3.2 改动要成链闭环

一个需求如果跨越多层，必须一次改完整。典型例子：

- Prompt 配置改动：
  - DTO
  - Service
  - Controller
  - 前端组件
  - SQL 默认值
  - 文档
- 会话 / memory 改动：
  - Graph 流式控制
  - Session registry
  - Hook
  - ChatMessage 过滤
  - 前端展示

### 3.3 避免引入与当前设计相反的能力

以下方向默认不要新增，除非明确提出需求：

- 多 agentType 模板体系
- 重新把 `scene` 作为 Prompt 业务维度
- 启动期自动 migration
- 只修真实库、不修 SQL 基线

## 4. 代码风格

详细编码规范参见以下文档：
- **后端 Java**：`docs/DEVELOPER_GUIDE.md`（JavaDoc、命名、4空格缩进、120字符行宽）和 `CONTRIBUTING-zh.md`（Spring 代码格式）
- **前端 Vue/TS**：`data-agent-frontend/README-CODE-STYLE.md`（Prettier、ESLint、vue-tsc）

### 4.1 后端

- 沿用当前 Java / Spring Boot / MyBatis 风格
- 以最小必要改动为原则，不做无关重构
- 注释可以写中文，但保持短而直接
- 公共默认值优先集中定义，避免散落魔法字符串
- 兼容逻辑必须"对外兼容、对内收敛"，不要把旧值继续传进核心路径

### 4.2 前端（面向 AI agent 编码）

- 沿用现有 Vue 3 + TypeScript + Element Plus 组件写法，不强推额外抽象
- 改接口时同步检查：
  - 保存参数
  - 编辑回填
  - 状态按钮
  - 批量操作
  - 完成后的 reload 行为
- 不要出现"UI 看起来能配，实际没落库"的假功能
- Vue 组件使用 PascalCase 命名，变量/函数使用 camelCase
- 接口类型优先使用 `interface` 而非 `type`，避免使用 `any`
- 修改前端请求参数时，同步检查 `api/` 层 -> 组件调用 -> 后端 DTO 是否对齐
- 新增组件时，必须检查 `router/index.ts` 路由注册和侧边栏菜单配置

## 5. 文档与待办

- 每次完成实质性改动后，更新 `docs/todolist.md`
- 已完成事项归档，不删除
- 当前待办只保留未完成项
- 如果实现决策发生变化，必须把旧口径一起改掉
- `todolist` 是当前重构状态的单一事实源
- 每次修复非直觉的 bug 或踩坑后，追加记录到 `docs/LESSONS.md`
- 新增/删除/重命名 vibe coding 文档时，更新 `docs/VIBE_CODING.md` 索引表

## 6. 验证要求

后端 Java 改动后，默认执行：

```powershell
mvn -pl data-agent-management -am -DskipTests compile
```

满足以下任一情况时，额外补前端验证：

- 改了前端请求参数
- 改了前端展示逻辑
- 改了组件交互状态

如果没有跑某项验证，要在结果里明确说明。

编译失败时的处理：
- 定位编译错误的具体文件和行号
- 修复后重新编译直到通过
- 如果因为缺少依赖或环境问题无法编译，必须在改动说明中列出

## 7. 提交前检查清单

- 是否仍然符合 `commonagent/system`
- 是否把 SQL 基线和测试基线同步
- 是否把 stop / cancel 的副作用处理完整
- 是否把 message visibility / memory eligibility 同步
- 是否更新 `docs/todolist.md`
- 是否留下无用文件、临时脚本、调试产物

## 8. 禁止事项

- 不要恢复已废弃的 `scene -> prompt` 设计
- 不要重新引入自动 migration runner
- 不要只改真实数据库而不改仓库 SQL
- 不要把完成项从 `todolist` 里直接删除
- 不要留下根目录临时文件

## 9. 智能自优化：总结、分析与错误防复发

### 9.1 任务完成后的自动总结

每次完成一个多步骤任务或非平凡改动后，在回复中简洁总结：
- 改动了什么（文件、方法、关键逻辑）
- 为什么这样改（根因 vs 症状）
- 是否有更优方案或遗留风险（如果存在，主动指出）

### 9.2 共性错误分析与规则写入

当同一类错误或同一类踩坑在不同时间/场景出现 **2 次及以上** 时，必须：
1. 主动识别共性根因，不满足于「修完即止」
2. 在 `docs/LESSONS.md` 中追加一条教训记录（现象 → 共性根因 → 修复方案）
3. 如果该问题可以被**工程约束拦截**（编译选项、lint 规则、代码模板、检查清单），则在 `AGENT.md` 的相应章节中追加一条永久约束规则，确保未来的 AI 会话自动遵守
4. 如果该问题无法被静态规则拦截，则在 `docs/LESSONS.md` 的教训中明确标注 `[CHECKPOINT]` 前缀，表示每次修改相关代码时必须人工/Agent 复查

### 9.3 主动质量分析

当用户要求或任务自然结束时，对当前改动范围做一次轻量分析：
- 改动是否引入新的技术债务
- 是否有可立即清理的无用代码、import、冗余注释
- 是否有需要同步更新的文档但尚未更新

## 10. 测试辅助与问题诊断

### 10.1 种子数据自动生成

在以下情况下，主动生成种子数据（不等待用户要求）：
- 新增或修改了数据库表结构（`schema.sql` 变更）
- 新增了需要填充初始数据的业务功能
- 修复的 bug 需要特定数据才能复现/验证

种子数据形式：
- 优先生成 SQL INSERT 脚本，放入 `data-agent-management/src/main/resources/sql/data.sql`（H2 测试基线的 `h2/data-h2.sql` 需同步）
- 如果需要大批量/随机数据，生成一个可复用的 Python/Shell 脚本，放入 `scripts/seed/` 目录，并在 `AGENT.md` 中引用

### 10.2 需求模糊时模拟人类行为推断

当用户的修复/需求描述不够具体时，按以下优先级推断真实需求：
1. 模拟典型用户操作链路：假设自己是该功能的真实使用者，推断最可能的操作步骤和预期结果
2. 查阅已有代码中的类似功能实现，推断一致的交互模式
3. 如果仍不确定，给出 2-3 种推断方案让用户选择，而不是直接放弃或用默认值

### 10.3 日志驱动的问题诊断

当遇到运行时错误（非编译错误）时：
1. 主动读取后端日志：`data-agent-management/logs/` 目录下的应用日志、Langfuse 链路追踪记录
2. 对照日志中的异常堆栈，定位到**具体的代码行**，不满足于「某模块可能有问题」
3. 如果日志级别不够或关键路径缺日志，在修复 bug 的同时补充必要日志（log.info/error），避免下次同类问题无迹可循
4. 日志分析结论写入修复说明中，格式：「日志位置 → 异常类型 → 根因代码位置 → 修复方案」
