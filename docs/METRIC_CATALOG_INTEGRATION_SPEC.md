# DataAgent 第三方指标目录与 HTTP 接口接入规范

> 版本：`1.0`
> 目录格式：`data-agent-metric-catalog/1.0`
> 状态：v1 已实现并作为第三方标准目录接入基线
> 已评审供应方：`data-metrics`

## 1. 文档目的

本文定义第三方指标系统接入 DataAgent 时需要提供的指标语义、HTTP 调用契约和目录格式。
规范面向“指标接口市场”等仅能提供基础指标名称、描述和接口文档的系统，不要求第三方同时
具备完整的指标治理、公式管理、SLA、负责人或多租户能力。

核心边界如下：

1. 一项指标对应一个可独立识别的 HTTP 调用定义。
2. `metricKey` 是搜索、描述、执行、上下架和本地修正的唯一主身份。
3. 第三方内部可以使用通用处理器，但提供给 DataAgent 的目录必须展开成唯一固定路径。
4. DataAgent 不通过 query、header 或 body 参数动态选择指标。
5. 指标响应可以保持第三方原有简单 JSON 结构，但必须提供 Schema、字段说明或示例。
6. DataAgent 保留原始响应，由确定性状态判断和 LLM 共同完成结果解释。

本文描述当前已实现的目标协议。兼容策略、模型调整和实施记录见
[指标目录标准化接入技术实施方案](METRIC_CATALOG_INTEGRATION_PLAN.md)。
部署切换、环境变量、验证和回滚操作见
[第三方指标目录切换与运维配置手册](METRIC_CATALOG_OPERATIONS.md)。

## 2. 规范关键词

- **必须（MUST）**：不满足时整份新目录不能激活。
- **应当（SHOULD）**：原则上提供；缺失时可以接入，但会降低检索、执行或解释质量。
- **可以（MAY）**：可选增强能力。

## 3. 接入模型

每个指标目录项由两部分组成：

```json
{
  "definition": {},
  "invocation": {}
}
```

- `definition` 描述“指标是什么”，供检索和展示。
- `invocation` 描述“指标怎么调用”，供参数澄清和 HTTP 执行。

目录内部仍可以组合成 DataAgent 当前的 `MetricCatalogEntry`：

```text
MetricCatalogEntry
├── MetricDefinition
├── MetricApiContract
├── MetricBinding
└── 服务及索引状态
```

本规范不建设多指标共享同一 Contract 的运行时模型。第三方必须在目录输出阶段完成路径展开。

## 4. 目录获取接口

第三方必须提供一个 HTTP GET 接口，一次返回当前完整有效目录：

```http
GET <catalog-path>
Accept: application/json
```

成功响应：

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

要求：

- 不依赖浏览器会话。
- 不要求 DataAgent 传入指标编码。
- 不在目录中包含 Token、Cookie、密码或签名密钥。
- 目录是完整快照，不是增量事件。
- 同一内容应保持稳定排序。
- 目录生成失败时不得返回半份目录，应返回非 2xx。
- DataAgent 收到非 2xx、非 JSON 或非法目录时保留上一次有效目录。
- `ETag`、`Last-Modified` 或业务 `revision` 至少应提供一种变更标识。

鉴权由网关或部署配置处理，LLM 不负责生成鉴权参数。

## 5. 标准目录根对象

```json
{
  "specVersion": "data-agent-metric-catalog/1.0",
  "sourceSystem": "metric-provider",
  "revision": "2026-07-24-001",
  "generatedAt": "2026-07-24T10:00:00+08:00",
  "metrics": []
}
```

| 字段 | 类型 | 要求 | 说明 |
|---|---|---|---|
| `specVersion` | string | 必须 | 固定为 `data-agent-metric-catalog/1.0` |
| `sourceSystem` | string | 必须 | 第三方系统稳定标识 |
| `revision` | string | 应当 | 目录内容版本；业务内容变化时变化 |
| `generatedAt` | string | 应当 | ISO 8601 时间，必须带时区 |
| `metrics` | array | 必须 | 当前完整有效指标列表 |

## 6. 指标定义 definition

### 6.1 最小结构

```json
{
  "metricKey": "sales.gmv",
  "metricCode": "GMV",
  "metricName": "成交金额",
  "description": "查询支付成功订单的成交金额",
  "aliases": []
}
```

### 6.2 字段要求

| 字段 | 类型 | 要求 | 说明 |
|---|---|---|---|
| `metricKey` | string | 必须 | 指标永久身份，全目录唯一 |
| `metricCode` | string | 应当 | 第三方业务编码 |
| `metricName` | string | 必须 | 面向用户的指标名称 |
| `description` | string | 应当 | 指标含义、口径或适用范围 |
| `aliases` | string[] | 可以 | 同义词和常用简称 |
| `examples` | string[] | 可以 | 用户自然语言问法示例 |
| `tags` | string[] | 可以 | 分类和检索标签 |
| `supportedGranularities` | string[] | 可以 | 支持的时间粒度 |
| `supportedDimensions` | string[] | 可以 | 支持的分组维度 |
| `supportedFilters` | string[] | 可以 | 支持的过滤字段 |

最低合法定义只要求 `metricKey` 和 `metricName`。缺少描述、别名和示例不会阻止接入，但供应方
需要接受自然语言召回效果可能下降。

### 6.3 metricKey 规则

推荐格式：

```text
<source-or-domain>.<metric-identity>
```

示例：

```text
sales.gmv
user.active-user-count
data-metrics.metric-service.37
```

推荐正则：

```regex
^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$
```

`metricKey` 一经发布，不得因名称、描述、服务编码、HTTP 路径或 `operationId` 变化而改变。

## 7. HTTP 调用定义 invocation

### 7.1 标准结构

```json
{
  "operationId": "querySalesGmv",
  "method": "POST",
  "path": "/api/metrics/gmv",
  "summary": "查询成交金额",
  "description": "按过滤条件查询成交金额",
  "parameters": [],
  "requestSchema": {},
  "response": {}
}
```

| 字段 | 类型 | 要求 | 说明 |
|---|---|---|---|
| `operationId` | string | 必须 | 全目录唯一调用标识 |
| `method` | string | 必须 | v1 支持 `GET`、`POST` |
| `path` | string | 必须 | 唯一固定相对路径 |
| `summary` | string | 应当 | 接口简要说明 |
| `description` | string | 应当 | 接口详细说明 |
| `parameters` | array | 必须 | 无参数时为空数组 |
| `requestSchema` | object/null | 按需 | POST JSON Body Schema |
| `response` | object | 必须 | 响应说明、Schema 和示例 |

指标查询必须只读、无业务写入副作用。v1 不接受 `PUT`、`PATCH`、`DELETE` 作为指标查询方法。

### 7.2 唯一固定路径

允许：

```text
/api/metrics/gmv
/api/metrics/order-count
/api/metrics/refund-amount
```

不允许：

```text
/api/metrics/{metricCode}
/api/metric/query?metricCode=GMV
https://metrics.example.com/api/metrics/gmv
```

第三方内部可以调用 `/api/metrics/{metricCode}`，但目录必须输出已经展开的字面路径。DataAgent
不会根据 `metricCode` 拼接路径，也不会把指标选择占位符交给 LLM。

普通业务路径参数可以保留，例如：

```text
/api/metrics/gmv/stores/{storeId}
```

前提是静态路径已经明确指标身份，`storeId` 只改变查询范围。

同一目录内 `path` 必须全局唯一；即使 HTTP 方法不同，也不允许两个指标复用相同 path。

## 8. 请求参数

### 8.1 参数结构

```json
{
  "name": "startDate",
  "location": "query",
  "jsonPath": "startDate",
  "type": "string",
  "format": "date",
  "required": true,
  "description": "开始日期，包含当天",
  "enumValues": [],
  "example": "2026-07-01",
  "defaultValue": null
}
```

| 字段 | 类型 | 要求 | 说明 |
|---|---|---|---|
| `name` | string | 必须 | 参数名称 |
| `location` | string | 必须 | `path`、`query`、`header`、`body` |
| `jsonPath` | string | body 应当 | Body 内字段路径 |
| `type` | string | 必须 | `string/integer/number/boolean/array/object` |
| `format` | string | 可以 | 如 `date`、`date-time` |
| `required` | boolean | 必须 | 是否必填 |
| `description` | string | 应当 | 业务含义和特殊约束 |
| `enumValues` | array | 可以 | 允许值 |
| `example` | any | 应当 | 合法示例 |
| `defaultValue` | any | 可以 | 默认值 |

GET 不得声明 request body。POST body 必须是 JSON 对象。

### 8.2 禁止动态选择指标

请求中不得通过以下类型的 query、header 或 body 参数切换指标：

```text
metricCode
metricId
indicatorCode
indicatorId
metricName
indicatorName
```

时间、粒度、维度、过滤条件、排序和 limit 可以动态提供。

### 8.3 复杂过滤条件

如果请求通过对象数组表达过滤条件，供应方必须同时提供：

- 数组参数的完整 Schema；
- 每个 item 字段的说明；
- 可用操作符；
- 至少一个合法示例；
- 需要用户补充的必填业务过滤条件说明。

必填业务过滤条件可以通过完整参数描述、Schema 描述和示例交给 LLM 理解。供应方若能提供
结构化 `requiredFilterFields` 和逐字段操作符信息，DataAgent 解析器可以进一步执行确定性校验，
但该增强不是 v1 接入前置条件。

## 9. 响应定义

### 9.1 标准结构

```json
{
  "description": "返回指标查询结果",
  "contentType": "application/json",
  "schema": {},
  "examples": []
}
```

要求：

1. 响应必须是合法 JSON，`Content-Type` 为 `application/json`。
2. 必须提供 response Schema 或完整成功示例，建议两者同时提供。
3. 动态业务字段必须通过父节点说明或 `additionalProperties` 表达。
4. 成功使用 HTTP 2xx；传输和系统错误使用对应的 4xx/5xx。
5. 如果历史接口以 HTTP 200 表达业务失败，目录或供应方解析 Profile 必须明确成功条件。
6. DataAgent 必须保留原始响应，不能因表格化失败丢失数据。
7. 数值使用 JSON number，日期使用 `yyyy-MM-dd`，时间使用带时区的 ISO 8601。

### 9.2 可选确定性提示

以下字段用于避免把业务失败误判为成功，或帮助 UI 提取主要结果；它们不是复杂响应映射 DSL：

```json
{
  "successCriteria": {
    "jsonPath": "$.code",
    "operator": "EQ",
    "expectedValue": 0
  },
  "resultPath": "$.data.list",
  "messagePath": "$.msg"
}
```

如果供应方不直接输出这些字段，专用解析器可以根据明确且稳定的供应方 Profile 补充；不能
根据自然语言描述在运行时临时猜测成功条件。

### 9.3 响应规模

指标接口应返回聚合结果，不应无限制返回明细。应提供时间范围、limit 或分页边界。建议单次：

```text
不超过 200 行
不超过 256 KiB
```

实际硬限制以 DataAgent 部署配置为准。

## 10. 目录统一校验

任何解析器输出后都必须经过同一个公共校验器：

### 10.1 文档级

- `specVersion` 受支持。
- `sourceSystem` 非空。
- `metrics` 非空。
- 目录 JSON 合法。

### 10.2 指标级

- `metricKey` 非空且唯一。
- `metricName` 非空。
- `metricCode` 存在时应当唯一。
- 指标能够生成非空检索文本。

### 10.3 调用级

- `operationId` 非空且唯一。
- `method` 为 GET 或 POST。
- `path` 以 `/` 开头、没有协议、域名和固定查询字符串。
- `path` 在目录内唯一。
- 不存在动态指标选择参数。
- 所有 path 占位符都有同名必填 path 参数。
- GET 不包含 request body。
- POST body 是 JSON 对象。
- response Schema 或成功示例至少存在一个。

### 10.4 激活规则

任一必须项失败时：

1. 整份新目录不激活。
2. 不静默跳过错误项并暴露半目录。
3. 保留上一次完整有效目录。
4. 返回可定位 `metricKey/operationId/path` 的错误摘要。

## 11. OpenAPI 3.0 映射

现有系统可以继续提供 OpenAPI 3.0.x。每个指标必须对应一个唯一 operation，并提供：

```yaml
operationId: querySalesGmv
x-data-agent-metric-id: sales.gmv
x-metric-name: 成交金额
x-metric-code: GMV
x-aliases:
  - 成交额
  - 交易金额
```

扩展字段必须是直接值：

```yaml
x-metric-code: GMV
```

不得输出包装对象：

```yaml
x-metric-code:
  value: GMV
```

请求和响应继续使用标准 OpenAPI `parameters`、`requestBody`、`responses`、Schema 和 examples。

## 12. data-metrics 供应方接入 Profile

### 12.1 评审结论

数据指标平台提供的 `data-agent-metric-catalog/1.0` 文档符合以下核心约束：

- 根节点直接返回完整标准目录，没有 `CommonResult` 包装。
- `metricKey`、`operationId` 和固定 path 均要求唯一。
- 平台内部通用处理器已经按指标服务编码展开为独立字面路径。
- 请求不通过运行时 `metricCode` 切换指标。
- 请求 Schema、过滤操作符、响应 Schema 和示例均有说明。
- 明确要求目录失败不返回半目录。

因此可以作为新解析器的首个标准目录实现。接入时还需处理四项供应方特征：

1. 查询接口以 HTTP 200 + `code!=0` 表达业务失败。
2. 主要结果集合固定在 `data.list`。
3. 请求 body 统一为顶层 `filterList` 数组，不能按对象叶子字段展开后重建。
4. v1 不开放 `isNull/isNotNull`，目录、LLM和执行器均不得生成这两个操作符。

### 12.2 基础地址和目录接口

示例基础地址：

```text
https://metrics.example.com
```

目录接口：

```http
GET /admin-api/dev/metricEmploy/catalog
Accept: application/json
```

目录中 path 已包含 `/admin-api`。实际请求地址为：

```text
baseUrl + path
```

不得同时在 `baseUrl` 和 `path` 重复 `/admin-api`。

目录错误示例：

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json
```

```json
{
  "code": "CATALOG_INVALID",
  "message": "指标目录生成失败",
  "requestId": "8a54805a4b5f4d77",
  "details": {
    "errors": [
      "存在重复的指标调用路径"
    ]
  }
}
```

### 12.3 指标目录项

```json
{
  "definition": {
    "metricKey": "data-metrics.metric-service.37",
    "metricCode": "flight-departure-count",
    "metricName": "每日出港航班总量",
    "description": "统计指定条件下每日出港航班总量",
    "aliases": [],
    "examples": [],
    "tags": [],
    "supportedGranularities": [],
    "supportedDimensions": [],
    "supportedFilters": [
      "CARRIER",
      "FLT_DATE"
    ]
  },
  "invocation": {
    "operationId": "queryMetricService37",
    "method": "POST",
    "path": "/admin-api/dev/metricEmploy/data/flight-departure-count",
    "summary": "查询每日出港航班总量",
    "description": "通过数据指标平台已发布服务查询每日出港航班总量",
    "parameters": [],
    "requestSchema": {},
    "response": {}
  }
}
```

### 12.4 指标请求

所有指标统一调用固定 POST 路径，并发送 JSON 对象：

```http
POST /admin-api/dev/metricEmploy/data/flight-departure-count
Content-Type: application/json
Accept: application/json
```

无过滤条件：

```json
{
  "filterList": []
}
```

有过滤条件：

```json
{
  "filterList": [
    {
      "field": "CARRIER",
      "filterCondition": "eq",
      "filterValue": "MU"
    },
    {
      "field": "FLT_DATE",
      "filterCondition": "ge",
      "filterValue": "2026-07-01"
    }
  ]
}
```

目录中 `parameters` 保持一个顶层数组参数：

```json
[
  {
    "name": "filterList",
    "location": "body",
    "jsonPath": "filterList",
    "type": "array",
    "required": true,
    "description": "指标过滤条件；无过滤条件时传空数组",
    "enumValues": [],
    "example": [
      {
        "field": "CARRIER",
        "filterCondition": "eq",
        "filterValue": "MU"
      }
    ],
    "defaultValue": []
  }
]
```

DataAgent 专用解析器必须以目录提供的顶层 `parameters` 作为可执行参数，不把
`requestSchema.properties.filterList.items.properties` 展开成 `filterList.field` 等伪 JSON Path。

供应方当前提供的完整 `requestSchema`：

```json
{
  "type": "object",
  "required": [
    "filterList"
  ],
  "properties": {
    "filterList": {
      "type": "array",
      "description": "指标过滤条件；无过滤条件时传空数组",
      "items": {
        "type": "object",
        "required": [
          "field",
          "filterCondition",
          "filterValue"
        ],
        "properties": {
          "field": {
            "type": "string",
            "description": "过滤字段",
            "enum": [
              "CARRIER",
              "FLT_DATE"
            ]
          },
          "filterCondition": {
            "type": "string",
            "description": "过滤操作符，允许值由字段类型决定",
            "enum": [
              "eq",
              "ne",
              "like",
              "notLike",
              "likeLeft",
              "notLikeLeft",
              "likeRight",
              "notLikeRight",
              "lt",
              "le",
              "gt",
              "ge"
            ]
          },
          "filterValue": {
            "type": "string",
            "description": "过滤值；当前接口统一按字符串接收"
          }
        }
      },
      "default": []
    }
  }
}
```

每项指标的 `field.enum` 根据其实际允许过滤字段生成，示例中的 `CARRIER/FLT_DATE` 不是所有
指标的固定字段。

### 12.5 过滤条件

`field` 的 enum 由当前指标实际允许字段生成。平台支持的操作符集合为：

| 字段类型 | 允许的 `filterCondition` |
|---|---|
| 字符串 | `notLikeLeft`、`likeLeft`、`notLikeRight`、`likeRight`、`notLike`、`like`、`ne`、`eq` |
| 整数 | `le`、`lt`、`ge`、`gt`、`ne`、`eq` |
| 日期时间 | `le`、`lt`、`ge`、`gt`、`ne`、`eq` |

目录可能给出各字段操作符的并集。LLM 根据参数说明、Schema和示例选择，最终由数据指标平台
校验。某些指标要求 `filterList` 必须包含特定 field，目录需要在参数描述、Schema说明和示例中
明确表达。

data-metrics v1 明确不支持：

```text
isNull
isNotNull
```

原因是当前服务端要求所有操作符的 `filterValue` 非 null，SQL 构造也按“字段、值”双参数调用
操作符；两个空值判断操作符属于单参数操作，现有链路不能可靠执行。省略或传 null 会被校验
拒绝，传空字符串可能导致过滤条件未生效。

供应方目录不得在 `filterCondition.enum` 中发布这两个值；DataAgent Parser、工具说明和执行前
校验也必须禁止它们。即使模型直接构造了包含这两个值的 arguments，服务端也不得发起指标
HTTP 请求。未来如需支持，必须通过目录契约升级明确新的请求结构和 `filterValue` 规则。

### 12.6 查询响应

成功示例：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "total": 2,
    "list": [
      {
        "FLT_DATE": "2026-07-22",
        "ID": 128
      },
      {
        "FLT_DATE": "2026-07-23",
        "ID": 135
      }
    ],
    "filterList": [
      {
        "field": "CARRIER",
        "filterCondition": "eq",
        "filterValue": "MU"
      }
    ]
  }
}
```

业务失败示例：

```json
{
  "code": 1001003002,
  "msg": "指标服务不存在或未发布",
  "data": null
}
```

供应方 Profile 的确定性解释规则：

```text
HTTP 为 2xx 且 code = 0  → 成功
HTTP 为非 2xx            → HTTP 失败
HTTP 为 2xx 且 code != 0 → 业务失败
主要结果集合              → $.data.list
业务消息                  → $.msg
```

DataAgent 解析后应等价生成：

```json
{
  "successCriteria": {
    "jsonPath": "$.code",
    "operator": "EQ",
    "expectedValue": 0
  },
  "resultPath": "$.data.list",
  "messagePath": "$.msg"
}
```

执行结果必须保留完整原始 JSON；`data.list` 可以额外转换为 `rows` 供结构化展示。

响应字段约定：

| JSON路径 | 类型 | 说明 |
|---|---|---|
| `code` | integer | 业务状态码，0表示成功 |
| `msg` | string | 业务提示或错误信息 |
| `data` | object/null | 指标查询结果；失败时可以为空 |
| `data.total` | integer | 结果总行数 |
| `data.list` | array | 主要指标结果行 |
| `data.filterList` | array | 本次请求过滤条件 |

供应方当前提供的响应 Schema：

```json
{
  "type": "object",
  "required": [
    "code",
    "msg",
    "data"
  ],
  "properties": {
    "code": {
      "type": "integer",
      "description": "业务状态码，0表示成功，非0表示失败"
    },
    "msg": {
      "type": "string",
      "description": "业务提示或错误信息"
    },
    "data": {
      "type": "object",
      "description": "指标查询结果",
      "properties": {
        "total": {
          "type": "integer",
          "format": "int64",
          "description": "结果总行数"
        },
        "list": {
          "type": "array",
          "description": "指标结果行；字段由指标动态决定",
          "items": {
            "type": "object",
            "additionalProperties": true
          }
        },
        "filterList": {
          "type": "array",
          "description": "本次请求的过滤条件",
          "items": {
            "type": "object"
          }
        }
      }
    }
  }
}
```

业务失败时 `data=null`，因此实现侧读取 `data.list` 前必须先完成业务成功判断，不能把缺少
`data.list` 单独视为系统异常。

## 13. 兼容性规则

以下变化不得改变 `metricKey`：

- 指标名称、描述、别名变化；
- 服务编码变化；
- path 或 `operationId` 变化；
- 请求或响应说明完善。

以下属于调用契约变化，供应方必须更新 `revision`：

- HTTP 方法或 path 变化；
- 删除请求字段；
- 可选参数或过滤字段变成必填；
- 参数类型、枚举或操作符变化；
- 响应字段类型变化；
- 业务成功条件变化。

目录中删除指标表示供应方不再提供该指标。DataAgent 同步完整新目录后，不再搜索、描述或执行
该来源指标；本地历史配置的处理由 DataAgent 自身生命周期规则决定。

## 14. 接入验收清单

- [ ] 目录无需浏览器会话即可访问。
- [ ] 根节点是完整目录，不是 CommonResult 包装或增量列表。
- [ ] 每项指标具有唯一且稳定的 `metricKey`。
- [ ] 每项指标具有唯一 `operationId`。
- [ ] 每项指标具有唯一固定 path。
- [ ] path 中不存在指标选择占位符。
- [ ] 请求中不存在动态指标选择参数。
- [ ] 所有必填参数具有类型、说明和示例。
- [ ] 复杂数组参数不会被错误展开为对象字段。
- [ ] 每个响应具有 Schema 或成功示例。
- [ ] DataAgent 能识别供应方业务成功条件。
- [ ] 原始响应 JSON 被完整保留。
- [ ] 主要结果集合可以展示或交给 LLM 解释。
- [ ] 目录校验失败时保留上一份有效目录。
- [ ] 目录、LLM和执行前校验均不允许 `isNull/isNotNull`。
