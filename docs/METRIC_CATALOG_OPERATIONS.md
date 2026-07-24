# 第三方指标目录切换与运维配置手册

本文面向部署和运维人员，说明如何在 DataAgent 中启用、切换、验证和回滚第三方指标目录。
第三方需要提供的目录内容和接口约束见
[第三方指标目录与 HTTP 接口接入规范](METRIC_CATALOG_INTEGRATION_SPEC.md)。

## 1. 先理解两个地址

指标能力同时使用两个地址，二者含义不同：

| 配置 | 含义 | 示例 |
|---|---|---|
| `DATA_AGENT_METRIC_SWAGGER_URL` | 完整的指标目录地址。变量名为兼容旧版保留，标准目录也使用它 | `https://metrics.example.com/admin-api/dev/metricEmploy/catalog` |
| `DATA_AGENT_METRIC_BASE_URL` | 指标业务接口的基础地址，不包含目录路径 | `https://metrics.example.com` |

标准目录中的每项指标自带固定调用路径，例如：

```text
/admin-api/dev/metricEmploy/data/flight-departure-count
```

实际调用地址由 `baseUrl + path` 组成。因此：

- `DATA_AGENT_METRIC_BASE_URL` 不得重复填写 `/admin-api`。
- `DATA_AGENT_METRIC_SWAGGER_URL` 必须是可以直接 GET 的完整目录地址。
- 两个地址可以指向不同网关，但业务调用网关必须能访问目录中声明的全部固定路径。
- 容器部署时，地址必须从 DataAgent 容器内部可访问；容器中的 `127.0.0.1` 指向容器自身。

## 2. 支持的目录模式

| 模式 | `DATA_AGENT_METRIC_PARSER_FORMAT` | 适用场景 |
|---|---|---|
| 标准指标目录 | `data-agent-catalog-v1` | 新接入系统；目录符合 `data-agent-metric-catalog/1.0` |
| OpenAPI 兼容模式 | `openapi3` | 继续使用现有 OpenAPI 3 指标文档 |

新第三方系统应优先使用标准指标目录。切换解析模式时必须同时切换到与该格式匹配的目录地址，
不能用 `data-agent-catalog-v1` 解析 OpenAPI，也不能用 `openapi3` 解析标准目录。

## 3. 配置项

### 3.1 必填配置

```dotenv
# 启用指标能力。
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED='true'

# 标准目录使用 data-agent-catalog-v1；旧 OpenAPI 使用 openapi3。
DATA_AGENT_METRIC_PARSER_FORMAT='data-agent-catalog-v1'

# 完整目录 URL。
DATA_AGENT_METRIC_SWAGGER_URL='https://metrics.example.com/admin-api/dev/metricEmploy/catalog'

# 指标业务接口基础 URL。
DATA_AGENT_METRIC_BASE_URL='https://metrics.example.com'
```

如需完全关闭指标能力：

```dotenv
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED='false'
```

### 3.2 建议显式配置

```dotenv
# 自动刷新周期，单位秒。
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_REFRESH_INTERVAL_SECONDS='1800'

# 拉取目录和调用指标接口的超时，单位毫秒。
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_TIMEOUT_MS='10000'

# 单次指标响应允许进入内存的最大字节数。
DATA_AGENT_METRIC_MAX_RESPONSE_BYTES='262144'

# 交给 Agent 展示和解释的最大结果行数。
DATA_AGENT_METRIC_MAX_RESULT_ROWS='200'
```

运维调整建议：

- 刷新周期不宜小于第三方目录的稳定生成周期，生产环境通常从 30 分钟开始。
- 超时应小于网关和上游连接的总超时，避免上层已经断开、后端仍占用请求。
- `MAX_RESPONSE_BYTES` 是单次响应硬上限，超过后本次调用失败；不要用它替代第三方分页或聚合。
- `MAX_RESULT_ROWS` 只限制结构化结果行数，不代表可以无限增大原始响应。

## 4. 标准目录配置示例

data-metrics 供应方的推荐配置：

```dotenv
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED='true'
DATA_AGENT_METRIC_PARSER_FORMAT='data-agent-catalog-v1'
DATA_AGENT_METRIC_SWAGGER_URL='https://metrics.example.com/admin-api/dev/metricEmploy/catalog'
DATA_AGENT_METRIC_BASE_URL='https://metrics.example.com'
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_REFRESH_INTERVAL_SECONDS='1800'
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_TIMEOUT_MS='10000'
DATA_AGENT_METRIC_MAX_RESPONSE_BYTES='262144'
DATA_AGENT_METRIC_MAX_RESULT_ROWS='200'
```

该模式要求目录根节点包含：

```json
{
  "specVersion": "data-agent-metric-catalog/1.0",
  "sourceSystem": "data-metrics",
  "revision": "2026-07-24-001",
  "metrics": []
}
```

data-metrics v1 不支持 `isNull` 和 `isNotNull`。如果供应方目录发布了这两个操作符，整份新目录
会校验失败并拒绝激活。

## 5. OpenAPI 兼容模式配置示例

继续使用旧 OpenAPI 指标目录时：

```dotenv
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED='true'
DATA_AGENT_METRIC_PARSER_FORMAT='openapi3'
DATA_AGENT_METRIC_SWAGGER_URL='https://legacy-metrics.example.com/v3/api-docs/metric'
DATA_AGENT_METRIC_BASE_URL='https://legacy-metrics.example.com'
```

OpenAPI 中应当保证一个 operation 对应一个指标。新系统不要再通过一个通用 operation 加
`metricCode` 参数动态选择数百个指标。

## 6. 上线前检查第三方接口

以下检查应从 DataAgent 实际运行节点或容器网络中执行。

### 6.1 检查网络、TLS 和内容类型

```bash
curl --fail --silent --show-error \
  --connect-timeout 5 \
  --max-time 15 \
  -H 'Accept: application/json' \
  'https://metrics.example.com/admin-api/dev/metricEmploy/catalog'
```

要求：

- HTTP 状态为 2xx。
- `Content-Type` 为 JSON。
- 响应是完整目录，不是登录页、网关错误页或增量数据。
- HTTPS 证书链被 DataAgent 所使用的 JVM 信任。

### 6.2 检查标准目录根对象

安装了 `jq` 时可以执行：

```bash
curl --fail --silent --show-error \
  'https://metrics.example.com/admin-api/dev/metricEmploy/catalog' |
  jq -e '
    .specVersion == "data-agent-metric-catalog/1.0"
    and (.sourceSystem | type == "string")
    and (.metrics | type == "array")
    and (.metrics | length > 0)
  '
```

同时人工确认：

- `metricKey`、`operationId` 和固定 path 在整份目录中唯一。
- 每个 path 都是以 `/` 开头的相对路径。
- 请求参数中没有用来动态选择指标的 `metricCode`、`metricKey` 或类似字段。
- 响应包含 Schema 或成功示例，并说明业务成功条件。
- data-metrics 目录没有发布 `isNull/isNotNull`。

### 6.3 抽样检查业务接口

至少选择一个无敏感数据的代表性指标，按照目录中的 method、path、参数和示例直接调用，
确认：

- 实际地址等于 `DATA_AGENT_METRIC_BASE_URL + invocation.path`。
- HTTP 状态、业务状态字段和目录声明一致。
- `resultPath` 能定位到实际结果。
- 响应大小和耗时在 DataAgent 配置上限内。

## 7. 切换步骤

### 7.1 记录切换前状态

保存旧配置，并记录当前状态：

```bash
curl --fail --silent --show-error \
  'http://data-agent.example.com/api/metric-capability/status'
```

至少记录：

- `ready`
- `metricCount`
- `onlineCount`
- `generation`
- `lastRefreshSuccessTime`
- `lastRefreshError`

### 7.2 更新部署配置

将第 3 节配置注入应用进程。Spring Boot 不会自动读取仓库根目录 `.env`；源码启动时需要先
导出变量，容器或 Kubernetes 部署时需要在部署清单中显式声明。

Docker Compose 示例：

```yaml
services:
  data-agent:
    environment:
      SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED: "true"
      DATA_AGENT_METRIC_PARSER_FORMAT: "data-agent-catalog-v1"
      DATA_AGENT_METRIC_SWAGGER_URL: "https://metrics.example.com/admin-api/dev/metricEmploy/catalog"
      DATA_AGENT_METRIC_BASE_URL: "https://metrics.example.com"
      SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_REFRESH_INTERVAL_SECONDS: "1800"
      SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_TIMEOUT_MS: "10000"
      DATA_AGENT_METRIC_MAX_RESPONSE_BYTES: "262144"
      DATA_AGENT_METRIC_MAX_RESULT_ROWS: "200"
```

Kubernetes Deployment 的 `env` 示例：

```yaml
env:
  - name: SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED
    value: "true"
  - name: DATA_AGENT_METRIC_PARSER_FORMAT
    value: "data-agent-catalog-v1"
  - name: DATA_AGENT_METRIC_SWAGGER_URL
    value: "https://metrics.example.com/admin-api/dev/metricEmploy/catalog"
  - name: DATA_AGENT_METRIC_BASE_URL
    value: "https://metrics.example.com"
```

### 7.3 重启或滚动发布

修改目录地址、解析格式、基础地址、刷新周期或超时后必须重启应用。原因是解析器配置、定时任务
和业务调用 WebClient 都在应用启动时读取配置。

`POST /api/metric-capability/refresh` 只会重新拉取当前进程已经加载的目录地址，不能代替配置
变更后的重启。

生产环境建议先发布一个新实例进行验证，再逐步替换旧实例。不要在所有实例上同时切换一个
未经预检的新目录。

### 7.4 等待目录激活

应用启动后会异步拉取目录。持续查询：

```bash
curl --fail --silent --show-error \
  'http://data-agent.example.com/api/metric-capability/status'
```

通过条件：

- `ready` 为 `true`。
- `metricCount` 与供应方当前目录数量相符。
- `onlineCount` 符合本地上下架配置预期。
- `generation` 非空，并在成功切换后发生变化。
- `lastRefreshSuccessTime` 是本次发布后的时间。
- `lastRefreshError` 为空。

也可以在“指标目录管理”页面检查目录状态、指标数量、固定调用路径和响应契约。

### 7.5 执行业务冒烟

使用已绑定 `builtin-metric-system` 技能的 Agent，至少验证：

1. 一个无需过滤条件的指标。
2. 一个带 `filterList` 的指标。
3. 一个无结果场景。
4. 一个供应方业务失败场景。

确认命中了预期 `metricKey`、调用了预期固定路径，且成功结果、业务错误和原始响应都能展示。

## 8. 手工刷新

供应方目录更新后，可以等待自动刷新，也可以触发：

```bash
curl --fail --silent --show-error \
  -X POST \
  'http://data-agent.example.com/api/metric-capability/refresh'
```

该接口只表示“刷新任务已触发”，不表示刷新已经成功。触发后必须轮询 `/status`，确认
`lastRefreshSuccessTime` 更新；如果 `lastRefreshFailureTime` 更新，则查看 `lastRefreshError`
和应用日志。

目录内容没有变化时，系统根据内容哈希跳过重复激活，`generation` 不一定变化。

## 9. 失败保护与回滚

运行中的实例遇到下列问题时，会拒绝新目录并保留该实例内存中的上一份有效快照：

- 目录地址不可达、超时或返回非 2xx。
- 响应为空或不是合法 JSON。
- 解析格式与目录内容不匹配。
- `metricKey`、operation 或 path 重复。
- 存在动态指标选择参数或非法路径。
- 请求、响应契约不完整。
- data-metrics v1 出现 `isNull/isNotNull`。
- 新 generation 写入向量库失败。

注意：上一份快照是进程内状态。实例重启后不能把“失败时保留旧快照”当成持久化回滚方案。
因此正式切换必须保留旧部署配置，并优先采用新实例验证或蓝绿发布。

回滚步骤：

1. 恢复旧的 parser format、目录 URL 和 base URL。
2. 重启或滚动回滚 DataAgent 实例。
3. 等待 `/api/metric-capability/status` 恢复 `ready=true`。
4. 比对 `metricCount`，并执行一个旧指标冒烟。

切换目录不会删除 MySQL 中按同一 `metricKey` 保存的本地名称、描述、别名和上下架配置。第三方
不得随意变更已有指标的 `metricKey`，否则会被视为一项新指标，本地配置不会自动迁移。

## 10. 鉴权与网络边界

当前 DataAgent 没有面向指标目录和业务接口的全局静态鉴权头配置。不得让 LLM 生成 Token、
Cookie、密码或签名参数。

如果第三方必须鉴权，应使用以下方式之一：

- 将 DataAgent 和指标系统部署在受控内网，通过网络策略、服务网格身份或源地址白名单授权。
- 在 DataAgent 与第三方之间部署可信反向代理，由代理注入固定鉴权信息；两个 URL 均指向代理。
- 由企业网关完成 mTLS 或服务身份认证，并让 JVM 信任相应证书链。

不要把密钥写入标准目录、调用参数示例、`metricKey`、日志或前端配置。若现有系统只能通过
业务参数传递密钥，应先完成网关改造，再接入 DataAgent。

## 11. 监控与告警建议

建议定期采集 `/api/metric-capability/status`，至少监控：

| 字段 | 告警建议 |
|---|---|
| `ready` | 持续为 `false` 立即告警 |
| `metricCount` | 相比基线大幅下降或变为 0 告警 |
| `lastRefreshFailureTime` | 出现新失败时间告警 |
| `lastRefreshError` | 非空时附带到告警，但注意不要转发敏感数据 |
| `lastRefreshSuccessSecondsAgo` | 超过计划刷新周期两倍告警 |
| `circuitBreakerState` | 持续为 `OPEN` 告警 |

重点日志关键字：

```text
Fetched metric OpenAPI document successfully
Metric catalog synced to vector store
Failed to refresh metric OpenAPI catalog
Calling metric system endpoint
```

日志中的 `OpenAPI` 和 `swaggerUrl` 是兼容旧命名，在标准目录模式下同样代表指标目录。

## 12. 常见故障

| 现象 | 常见原因 | 处理 |
|---|---|---|
| 启动后一直 `not_ready` | 容器无法访问目录、解析模式错误、Embedding/向量库不可用 | 检查 `lastRefreshError`、容器内网络和应用日志 |
| 目录返回 200 但解析失败 | 返回了登录页、CommonResult 包装或格式与 parser 不匹配 | 检查响应体根节点和 `Content-Type` |
| 指标数量为 0 | 目录为空、全部校验失败或新目录未激活 | 直接检查供应方目录数量和校验错误 |
| 调用地址出现重复 `/admin-api` | `baseUrl` 错误包含了目录 path 前缀 | 将 base URL 调整为协议、域名和必要基础上下文 |
| 主机能访问、容器不能访问 | 使用了 `127.0.0.1`、DNS或网络策略未开放 | 从应用容器内执行同一检查 |
| HTTPS 握手失败 | JVM 不信任企业 CA、证书域名不匹配 | 配置 JVM truststore 或修复网关证书 |
| 手工刷新后状态未立即变化 | 刷新接口异步执行 | 轮询 `/status`，不要只看 refresh 返回值 |
| 新配置没有生效 | 只修改了 `.env` 或 ConfigMap，没有重启进程 | 确认变量进入容器并滚动重启 |
| 供应方返回 HTTP 200 但查询失败 | 响应业务状态不是目录声明的成功值 | 检查 `successCriteria`、`messagePath` 和供应方日志 |
| 响应过大 | 指标未聚合、返回明细过多 | 让供应方提供聚合/分页，谨慎调整响应上限 |

## 13. 上线确认单

- [ ] 已从 DataAgent 运行网络直接访问目录和抽样业务接口。
- [ ] parser format 与目录格式一致。
- [ ] 目录 URL 和 base URL 没有重复路径。
- [ ] 标准目录通过唯一性、固定路径和响应契约检查。
- [ ] 已确认目录和调用链不依赖浏览器 Cookie。
- [ ] 鉴权由可信网络、网关或代理完成，目录中没有密钥。
- [ ] 已保存旧配置并准备回滚。
- [ ] 新实例 `/status` 为 ready，数量与供应方一致。
- [ ] 已验证无过滤、带过滤、无结果和业务失败场景。
- [ ] 已配置刷新失败、指标数量和熔断状态监控。
