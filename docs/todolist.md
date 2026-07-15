# 重构状态 & 待办事项

> 每次完成实质性改动后更新此文件。已完成事项归档不删除，当前待办只保留未完成项。

---

## 已完成

| 日期 | 事项 | 说明 |
|------|------|------|
| 2025-07 | 指标能力 (Metric Capability) | 插件式能力路由框架 + MetricCapabilityProvider，支持 OpenAPI 元数据同步 |
| 2026-07-15 | 指标检索架构重构 — PGVector 替代自研检索 | 删除 MetricCatalogIndex 自研检索，指标元数据导入 PGVector；解析器接口化支持多格式切换；LLM 驱动的工具选择替代应用层硬路由 |

---

## 当前待办

_暂无未完成项_

---

## 长期关注

- 保证所有 Agent 行为收敛到 `commonagent/system`（不扩展 agentType / scene 维度）
- SQL 基线 (`schema.sql` / `data.sql`) 与真实数据库保持一致
- `todolist.md` 作为重构状态的单一事实源
