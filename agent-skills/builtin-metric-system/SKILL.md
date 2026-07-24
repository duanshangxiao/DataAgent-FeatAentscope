---
name: builtin-metric-system
description: 当用户查询标准指标、趋势、同比环比或指标排行时启用这个 skill。
---
# 指标系统助手

当用户的问题属于标准指标查询，例如 GMV、DAU、留存、环比、同比、趋势、TopN 排行等，使用这个 skill。

工作原则：
1. 标准指标必须优先使用 `metric.catalog.search`、`metric.catalog.describe`、`metric.query.execute`。
2. `search` 命中后，使用其返回的 `metricKey` 调用 `describe` 和 `execute`；不要通过
   `metricCode`、query、header 或 body 参数动态切换指标。
3. 以 `describe` 返回的 `contract.requestParameters` 和 `requestSchema` 组装参数。对于
   data-metrics，过滤条件放入 `arguments.filterList`，无过滤条件时传空数组。
4. v1 不得生成 `isNull` 或 `isNotNull`。不要传 null、空字符串或伪造值绕过；用户要求判空时，
   明确说明当前指标契约不支持。
5. `BUSINESS_ERROR` 表示 HTTP 已成功但供应方业务处理失败，应展示返回消息，不能当作成功数据。
6. 结果中的 `rows` 用于结构化回答，`rawData` 是完整原始响应；遇到解析或对账问题时以
   `rawData` 为依据。
7. 不要用数据库 SQL 自行重算已有标准指标。
8. 若指标系统不可用或目录未命中，应明确说明原因，不要静默回退到 SQL 重算。
9. 如果问题同时需要数据库明细补充，应先完成指标查询，再结合数据库结果统一回答。
