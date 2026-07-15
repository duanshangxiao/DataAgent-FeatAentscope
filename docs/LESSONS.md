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

## 2026-07-15: 指标目录代码卫生问题修复（4 项）

### 1. searchableText 遗漏 metricCode 和 metricName
- **现象**：embedding 向量和关键词匹配的 haystack 文本中不包含 `metricCode()` 和 `metricName()`，这两个字段是 OpenAPI 文档中最重要的标识字段却未纳入检索
- **根因**：`searchableText()` 只拼接了 `summary`、`description`、`operationId`、`path`、`aliases`、`tags`、`requestParameters`
- **修复**：在 `searchableText()` 开头追加 `metricCode()` 和 `metricName()`

### 2. @Scheduled 使用脆弱 SpEL 解析字符串配置
- **现象**：`fixedDelayString = "#{T(java.lang.Long).parseLong('${...:1800}') * 1000}"` runtime 解析，属性名以字符串硬编码在 SpEL 中
- **根因**：`fixedDelay` 不支持 expression，工程上走了 SpEL 绕路
- **修复**：新增 `refreshIntervalMillis()` 方法，SpEL 改为 `"#{@metricOpenApiSyncService.refreshIntervalMillis}"`

### 3. circuitBreakerHalfOpenMaxCalls 类型不一致
- **现象**：`MetricCapabilityProperties` 中为 `long`（默认 2），`MetricCircuitBreaker.configure()` 接收 `int`，调用方做了 `(int)` 强制转换
- **根因**：属性定义时未注意与目标方法签名对齐
- **修复**：`circuitBreakerHalfOpenMaxCalls` 改为 `int`，移除调用方 `(int)` 转换

### 4. embeddingCoarseRank 硬编码 0.3 相似度阈值
- **现象**：`embeddingCoarseRank()` 中 `.filter(entry -> entry.getValue() > 0.3D)` 硬编码，无法根据实际数据分布调优
- **根因**：阈值未外置为配置项
- **修复**：新增 `MetricCapabilityProperties.embeddingMinSimilarity`（默认 0.3），`embeddingCoarseRank` 引用配置项

---

## 2026-07-15: 指标能力系统可观测性缺失

- **现象**：指标目录刷新成功/失败、定义数量、熔断器状态无法从外部查询，只能通过应用日志观察。指标目录静默退化（如定时刷新失败导致 catalog 过期）无法被监控系统告警。
- **根因**：项目无 Micrometer/Actuator 依赖，MetricCapability 组件状态仅通过 SLF4J 日志记录。
- **修复**：新增 `MetricCapabilityStatus` JMX MBean（零依赖），暴露 6 个可查询属性：`ready`、`definitionCount`、`lastRefreshSuccessTime`、`lastRefreshFailureTime`、`lastRefreshError`、`circuitBreakerState`。分别接入 `MetricOpenApiSyncService`、`MetricCircuitBreaker` 的生命周期。
- **教训**：[CHECKPOINT] 后续 capapability provider 新增时同步接入 `MetricCapabilityStatus`；如果将来引入 Micrometer，应优先将 MBean 属性迁移为 Prometheus gauge。

---

## 2026-07-15: 指标检索架构重构 — PGVector 替代自研检索

- **背景**：原 `MetricCatalogIndex` 自研了两阶段检索（embedding 粗排 + 关键词精排），在 OpenAPI 技术文档而非业务语义文本上做匹配，中英文跨语言场景准确率不可靠。
- **方案**：
  1. 指标元数据导入 PGVector（复用项目已有的 `AgentVectorStoreService` + `DocumentConverterUtil`），利用 PGVector 原生 embedding 检索
  2. 混合检索交给已有的 `HybridRetrievalStrategy` 体系（ES 可用时自动并行关键词+向量，不可用时纯向量）
  3. LLM 替代应用层路由决策（`route()` 不再做硬性 `METRIC_ONLY` / `ABSTAIN` 判断，统一返回 `MIXED` 允许多工具共存，LLM 自行判断是否走指标路径）
  4. 解析器接口化（`MetricMetadataParser` → `MetricMetadataParserFactory`），支持未来切换非 OpenAPI 格式
- **删除**：`MetricCatalogIndex.java`、`MetricCatalogSearchCandidate.java`
- **新增**：`MetricMetadataParser.java`、`MetricMetadataParserFactory.java`、`MetricDefinitionLookup.java`、`DocumentConverterUtil.convertMetricToDocument()`
- **改动**：`MetricOpenApiParser` 实现接口、`MetricOpenApiSyncService` 输出到 PGVector、`MetricToolProvider` 搜索切到 `AgentVectorStoreService`、`MetricCapabilityProvider.route()` 简化、`CapabilityRoutingService` 取消工具裁剪

---

## 2026-07-15: Spring Bean 循环依赖 — 两次启动失败

- **现象**：`mvn compile` 通过但 `spring-boot:run` 报 `APPLICATION FAILED TO START`，循环依赖。第一次：`MetricCapabilityProvider → MetricOpenApiSyncService → VectorStore`；第二次：`@Scheduled(fixedDelayString = '#{@metricOpenApiSyncService...}')` 在 bean 初始化期间通过 SpEL 自引用
- **共性根因**：
  1. `compile` 不检查 Spring 容器初始化，循环依赖、bean 后处理器冲突都是运行时故障
  2. 改动 Bean 构造器依赖/注解（`@Scheduled`、`@Component` 注入关系）后没有做容器级验证
- **修复**：
  1. `MetricCapabilityProvider` 删除对 `MetricOpenApiSyncService`（基础设施层）的注入，改用轻量 `MetricCapabilityStatus`
  2. `@Scheduled` SpEL 从 `#{@self}` 改为 `${property:default}000` 属性占位符
- **教训**：[已写入 AGENT.md §6] 修改 Bean 注入/构造器/依赖关系/`@Scheduled`/`@Async`/`@Configuration` 后必须执行 `mvn spring-boot:run` 启动验证，不能在 `compile` 通过后就认为完成

---

## 模板

```markdown
## YYYY-MM-DD: 简短标题

- **现象**：
- **根因**：
- **修复**：
- **教训**：
```
