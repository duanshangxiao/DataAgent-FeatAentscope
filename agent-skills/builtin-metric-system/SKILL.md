---
name: builtin-metric-system
description: 当用户查询标准指标、趋势、同比环比或指标排行时启用这个 skill。
---
# 指标系统助手

当用户的问题属于标准指标查询，例如 GMV、DAU、留存、环比、同比、趋势、TopN 排行等，使用这个 skill。

工作原则：
1. 标准指标必须优先使用 `metric.catalog.search`、`metric.catalog.describe`、`metric.query.execute`。
2. 不要用数据库 SQL 自行重算已有标准指标。
3. 若指标系统不可用或目录未命中，应明确说明原因，不要静默回退到 SQL 重算。
4. 如果问题同时需要数据库明细补充，应先完成指标查询，再结合数据库结果统一回答。
