# 教训日志

> 每次发现并修复一个 bug、踩到一个坑、或者碰到一个非直觉的设计约束后，在这里追加一条。
> 格式：`## 日期: 一句话标题` → 现象 → 根因 → 修复/规避方案

---

## 2025-07: OpenAPI 解析器不识别 `*/*` media type

- **现象**：MetricCapabilityProvider 启动后 `definitions` 始终为空，指标目录永远无法就绪
- **根因**：OpenAPI 解析器只匹配 `application/json` 类型的响应，第三方指标系统返回的 content-type 为 `*/*`，解析器直接跳过
- **修复**：`resolveContentSchema` 中按 `application/json` → `*/*` → 遍历所有 media type 的顺序兜底匹配
- **教训**：集成外部 OpenAPI/Swagger 时，不能假设对方遵守 content-type 约定，需要做兼容解析

---

## 2026-07-14: 指标能力集成方案架构评估 — 4 类问题

### P0: 路由召回仅基于关键词匹配，无语义检索
- **现象**：用户自然语言问题（如"上个月销售额怎么样"）无法匹配 OpenAPI 文档中的指标名（如 `revenue_amount`）
- **根因**：`MetricCatalogIndex.search()` 纯字符串包含+ngram 匹配，无语义向量检索
- **修复**：注入 `EmbeddingModel`，新增两阶段检索——先对所有 `MetricDefinition` 做 embedding 向量相似度粗排取 top-k，再在精排阶段复用现有关键词评分逻辑
- **改动**：`MetricCatalogIndex` 新增 embedding 索引，`MetricCapabilityProperties` 加 `embeddingEnabled` 开关

### P1: 无熔断器保护 + 路由后无降级回退
- **现象**：指标系统宕机时每个请求阻塞 10 秒超时；METRIC_ONLY 路由下工具被裁剪无法回退 DB
- **根因**：`executeHttp()` 无熔断保护，`isToolAllowed()` 严格按路由类型裁剪工具集
- **修复**：新增 `MetricCircuitBreaker`（轻量实现，计数失败→OPEN→半开探测→CLOSE）。CB OPEN 时 `route()` 强制返回 ABSTAIN；运行时指令中增加"指标系统不可用时允许 DB 近似计算"的提示
- **改动**：新建 `MetricCircuitBreaker.java`，修改 `route()` / `executeHttp()` / `buildRuntimeInstructions()`

### P2: 评分权重硬编码 + enabledForAgent 每次查 DB
- **现象**：评分各维度权重（0.45/0.30/0.15/0.12）、路由阈值（0.75）、澄清阈值系数（0.6）均硬编码，无法调优；每次请求查一次 agent-skill 绑定
- **根因**：评分逻辑中魔法数字直接写死；`enabledForAgent()` 无缓存
- **修复**：权重/阈值外置为 `MetricCapabilityProperties` 字段；`enabledForAgent()` 引入 Caffeine 本地缓存（TTL=60s）
- **改动**：`MetricCapabilityProperties` + `MetricCapabilityProvider` + pom.xml 加 caffeine

### P3: isMetricOperation 关键词硬编码
- **现象**：判断是否为指标接口的关键词列表 `["metric","gmv","dau"]` 硬编码，新业务指标无法自动识别
- **根因**：`isMetricOperation()` 中 keywords 为固定列表
- **修复**：关键词列表外置为 `MetricCapabilityProperties.metricKeywords` 配置项，默认值保留原有列表
- **改动**：`MetricCapabilityProperties` + `MetricOpenApiParser`

---

## 模板

```markdown
## YYYY-MM-DD: 简短标题

- **现象**：
- **根因**：
- **修复**：
- **教训**：
```
