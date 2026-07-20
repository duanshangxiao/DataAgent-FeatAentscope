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
| 2026-07-17 | 工具结果双路径渲染统一 + 折叠体验修复 | 工具节点持久化改为存储原始 JSON(而非扁平 HTML)，历史路径复用 ToolResultDisplay 实现结构化渲染；新增 4 个缺失区域的折叠控件(指标候选/查询结果/语义命中/知识命中)；新增卡片级折叠入口(.tool-result-title)；移除 ToolResultDisplay 外层重复的 agent-response-block；折叠默认值收敛到 sectionDefaults 常量表 |

---

## 当前待办

_暂无未完成项_

---

## 长期关注

- 保证所有 Agent 行为收敛到 `commonagent/system`（不扩展 agentType / scene 维度）
- SQL 基线 (`schema.sql` / `data.sql`) 与真实数据库保持一致
- `todolist.md` 作为重构状态的单一事实源
