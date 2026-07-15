# 指标模拟接口应用 — 接口文档规范

> **目标读者**：Agent / 开发者，用于创建一个 Spring Boot 指标接口模拟应用，为 DataAgent 项目的指标能力模块（metric capability）提供本地测试环境。

---

## 1. 背景

DataAgent 的指标能力模块通过拉取远程指标系统的 OpenAPI 文档（Swagger）来动态发现可用的指标查询接口，并将自然语言查询自动路由到匹配的指标接口。

本规范描述了指标模拟应用需要遵循的接口契约，使 DataAgent 能够正确解析、索引并调用模拟接口。

---

## 2. 整体架构

```
DataAgent (启动时)
    │
    ├─ GET {swaggerUrl}  →  拉取 OpenAPI 文档 (JSON)
    │                        MetricOpenApiParser 解析
    │                        生成 MetricDefinition 列表
    │                        MetricCatalogIndex 建立索引
    │
    └─ POST {baseUrl}{path}  →  运行时调用指标接口
                                 normalize 处理响应
```

两个关键配置：

| 配置项 | 说明 | 示例 |
|---|---|---|
| `swaggerUrl` | OpenAPI 3.0 文档地址 | `http://localhost:8081/v3/api-docs/metric` |
| `baseUrl` | 指标接口基础地址 | `http://localhost:8081` |

---

## 3. OpenAPI 文档格式（swaggerUrl 返回的内容）

### 3.1 最小结构

```json
{
  "openapi": "3.0.0",
  "paths": {
    "/metrics/gmv/trend": {
      "post": {
        "operationId": "gmvTrend",
        "summary": "GMV 趋势查询",
        "description": "按时间范围和粒度查询 GMV 趋势数据",
        "tags": ["metric-api", "sales"],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/GmvTrendRequest"
              }
            }
          }
        },
        "responses": {
          "200": {
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/GmvTrendResponse"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "GmvTrendRequest": {
        "type": "object",
        "required": ["granularity"],
        "properties": {
          "granularity": {
            "type": "string",
            "enum": ["DAY", "WEEK", "MONTH"],
            "default": "DAY",
            "description": "时间粒度"
          },
          "startDate": {
            "type": "string",
            "example": "2025-01-01",
            "description": "开始日期"
          },
          "endDate": {
            "type": "string",
            "example": "2025-01-31",
            "description": "结束日期"
          }
        }
      },
      "GmvTrendResponse": {
        "type": "object",
        "properties": {
          "metricCode": {
            "type": "string",
            "example": "gmv_daily_trend"
          },
          "metricName": {
            "type": "string",
            "example": "GMV 日趋势"
          },
          "rows": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/GmvTrendRow"
            }
          }
        }
      },
      "GmvTrendRow": {
        "type": "object",
        "properties": {
          "date": { "type": "string", "example": "2025-01-01" },
          "gmv": { "type": "number", "example": 1234567.89 },
          "orderCount": { "type": "integer", "example": 5432 }
        }
      }
    }
  }
}
```

### 3.2 支持的 HTTP 方法

解析器遍历以下方法：`get`, `post`, `put`, `delete`, `patch`

---

## 4. 指标接口识别规则 — isMetricOperation()

解析器通过以下优先级判断一个接口是否为「指标接口」（满足任一条件即可）：

| 优先级 | 条件 | 示例 |
|---|---|---|
| 1 | operation 对象上有 `x-metric-code` 或 `x-metric-name` 扩展字段 | `"x-metric-code": "gmv_trend"` |
| 2 | response/request schema 中存在名为 `metricCode` 或 `metricName` 的字段且有 example 值 | `"metricCode": {"type": "string", "example": "gmv_trend"}` |
| 3 | 路径/summary/description/tags 包含关键字 | 默认关键字见下方 |

**默认关键字列表**（可通过配置 `metricKeywords` 覆盖）：

```
metric, metrics, 指标, gmv, dau, mau, 留存, 活跃
```

**建议**：最可靠的方式是给每个指标接口添加 `x-metric-code` 和 `x-metric-name` 扩展字段。

---

## 5. 扩展字段

在 operation 级别，解析器支持以下自定义扩展字段：

| 字段 | 说明 | 示例 |
|---|---|---|
| `x-metric-code` | 指标唯一编码（最高优先级） | `"gmv_daily_trend"` |
| `x-metric-name` | 指标中文名称 | `"GMV 日趋势"` |
| `x-aliases` | 别名列表（用于语义匹配） | `["成交额", "总交易额"]` |

> 不使用这些字段时，解析器会从 `operationId`、`summary`、tags 中等兜底取值。

---

## 6. 参数规范

解析器从两个位置提取参数：

### 6.1 路径参数 & 查询参数（parameters 节点）

```yaml
parameters:
  - name: storeId
    in: query
    required: true
    description: 门店ID
    schema:
      type: integer
      example: 1001
  - name: granularity
    in: query
    required: false
    schema:
      type: string
      enum: [DAY, WEEK, MONTH]
```

解析器识别 `in` 字段的值：`query`（查询参数）、`path`（路径替换）、`header`（请求头）。

### 6.2 请求体参数（requestBody → content → schema）

```yaml
requestBody:
  content:
    application/json:
      schema:
        type: object
        required: [startDate, endDate]
        properties:
          startDate:
            type: string
            example: "2025-01-01"
          endDate:
            type: string
            example: "2025-01-31"
```

- `location` 为 `"body"`
- `jsonPath` 为 JSON 对象路径（支持嵌套 `timeRange.startDate`）
- 支持嵌套 object、array items 展开

### 6.3 参数支持的字段

| 字段 | 说明 |
|---|---|
| `type` | schema type：`string`, `integer`, `number`, `boolean`, `array`, `object` |
| `required` | 必填标记 |
| `enum` | 枚举值列表 |
| `example` | 示例值 |
| `default` | 默认值 |
| `description` | 参数描述 |

---

## 7. 响应格式规范

### 7.1 Content-Type 支持

解析器按以下优先级匹配响应 schema：

1. `application/json`
2. `*/*`
3. 任意其他 media type（兜底）

### 7.2 状态码

解析器按以下顺序查找响应 schema：

1. `200`
2. `201`
3. `default`

### 7.3 数据提取规则 — normalize()

返回的 JSON 响应中，解析器通过 `locateRowsNode()` 按以下优先级定位数据数组：

1. 如果根对象是数组 → 直接使用
2. 查找字段：`rows` → `data` → `list` → `items` → `result`
3. 如果都找不到 → 使用根对象本身

**推荐格式**：

```json
{
  "metricCode": "gmv_daily_trend",
  "metricName": "GMV 日趋势",
  "rows": [
    { "date": "2025-01-01", "gmv": 1234567.89, "orderCount": 5432 },
    { "date": "2025-01-02", "gmv": 987654.32, "orderCount": 4321 }
  ]
}
```

也可以使用 `data` 替代 `rows`：

```json
{
  "data": [
    { "date": "2025-01-01", "gmv": 1234567.89 }
  ]
}
```

**粒度（granularity）枚举值**：响应 schema 中名称包含 `granularity`、`period`、`cycle` 的 enum 字段会被自动提取。

**维度（dimension）字段**：名称包含 `dimension`、`group`、`groupBy` 的属性名会被提取。

**过滤（filter）字段**：名称包含 `filter`、`where` 的属性名会被提取。

---

## 8. 运行时调用格式

DataAgent 调用指标接口时，会：

1. **路径参数** `{param}` → 用参数值替换 URL 中的占位符
2. **查询参数** (`location = "query"`) → 拼接到 URL `?key=value`
3. **请求体参数** (`location = "body"`) → 构造 JSON body（嵌套字段用点号路径还原为 JSON 对象）
4. **Header 参数** (`location = "header"`) → 添加到 HTTP 请求头

**请求示例**（以 POST /metrics/gmv/trend 为例）：

```
POST http://localhost:8081/metrics/gmv/trend
Content-Type: application/json

{
  "granularity": "DAY",
  "startDate": "2025-01-01",
  "endDate": "2025-01-31"
}
```

---

## 9. Spring Boot 模拟应用实现指南

### 9.1 项目依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.8</version>
</dependency>
```

### 9.2 关键配置

```yaml
# application.yml
server:
  port: 8081

springdoc:
  api-docs:
    path: /v3/api-docs/metric     # 即 swaggerUrl
  swagger-ui:
    path: /swagger-ui.html
```

### 9.3 Controller 示例

```java
@RestController
@RequestMapping("/metrics")
@Tag(name = "metric-api", description = "指标接口")
public class MetricMockController {

    @Operation(
        operationId = "gmvTrend",
        summary = "GMV 趋势查询",
        description = "按时间粒度和日期范围查询 GMV 趋势数据",
        extensions = {
            @Extension(name = "x-metric-code", properties = {
                @ExtensionProperty(name = "value", value = "gmv_daily_trend")
            }),
            @Extension(name = "x-metric-name", properties = {
                @ExtensionProperty(name = "value", value = "GMV 日趋势")
            }),
            @Extension(name = "x-aliases", properties = {
                @ExtensionProperty(name = "values", value = "[\"成交额\", \"交易额\"]")
            })
        }
    )
    @PostMapping("/gmv/trend")
    public GmvTrendResponse getGmvTrend(@RequestBody GmvTrendRequest request) {
        // 模拟返回数据
        List<GmvTrendRow> rows = generateMockRows(request);
        GmvTrendResponse response = new GmvTrendResponse();
        response.setMetricCode("gmv_daily_trend");
        response.setMetricName("GMV 日趋势");
        response.setRows(rows);
        return response;
    }

    @Operation(
        operationId = "storeSalesRanking",
        summary = "门店销售额排行",
        extensions = {
            @Extension(name = "x-metric-code", properties = {
                @ExtensionProperty(name = "value", value = "store_sales_ranking")
            })
        }
    )
    @PostMapping("/store/sales/ranking")
    public Map<String, Object> getStoreSalesRanking(@RequestBody Map<String, Object> request) {
        // 用 Map<String, Object> + rows/data 字段返回，对解析器更友好
        return Map.of(
            "data", generateStoreRankingRows()
        );
    }
}
```

### 9.4 关于 OpenAPI 扩展字段 (@Extension)

**重要**：SpringDoc 的 `@Extension` 注解渲染到 JSON 时格式为 `x-metric-code: { value: "xxx" }`（带 wrapper），但解析器期望的格式是 `x-metric-code: "xxx"`（直接值）。

**推荐方案**：不依赖 `@Extension`，而是手动提供 Swagger JSON 文件覆盖，或在 Controller 中使用 `@Hidden` 隐藏真实端点，再用自定义 JSON 作为 OpenAPI 文档：

```java
@RestController
public class OpenApiController {

    @GetMapping(value = "/v3/api-docs/metric", produces = "application/json")
    public String getOpenApiDoc() {
        // 返回手写的完整 OpenAPI JSON，确保 x-metric-code 格式正确
        return """
        {
          "openapi": "3.0.0",
          "paths": { ... },
          "components": { ... }
        }
        """;
    }
}
```

或者更简单的方式：将 OpenAPI JSON 文件放在 `src/main/resources/static/` 目录下，直接通过静态资源访问。

### 9.5 数据格式最佳实践

为确保 DataAgent 能正确提取数据：

1. **响应体必须包含数据列表字段**：使用 `rows` 或 `data` 作为数据列表的 key
2. **schemamodel 中的关键标识**：在 response schema 中包含 `metricCode` 和 `metricName` 字段（带 example 值），以便解析器在没有 `x-metric-code` 扩展字段时仍能识别
3. **枚举粒度**：在请求 schema 中将粒度字段命名为 `granularity`、`period` 或 `cycle`，并定义 enum 值
4. **时间范围**：请求参数中至少包含一个时间相关的字段（如 `startDate`、`endDate`、`date` 等）

---

## 10. 完整的模拟端点示例

### 10.1 GMV 趋势

```
POST /metrics/gmv/trend
Request:  { "granularity": "DAY", "startDate": "2025-01-01", "endDate": "2025-01-07" }
Response: { "metricCode": "gmv_daily_trend", "metricName": "GMV 日趋势",
            "rows": [{ "date": "2025-01-01", "gmv": 123456.78, "orders": 1234 }, ...] }
```

### 10.2 门店销售额趋势

```
POST /metrics/store/sales/trend
Request:  { "storeId": 1001, "granularity": "DAY", "startDate": "2025-01-01", "endDate": "2025-01-31" }
Response: { "data": [{ "date": "2025-01-01", "storeId": 1001, "sales": 56789.01 }, ...] }
```

### 10.3 活跃用户数趋势

```
GET /metrics/dau/trend?granularity=DAY&startDate=2025-01-01&endDate=2025-01-31
Response: { "rows": [{ "date": "2025-01-01", "activeUsers": 89234, "newUsers": 1234 }, ...] }
```

### 10.4 支付金额排行

```
POST /metrics/payment/ranking
Request:  { "granularity": "MONTH", "startDate": "2025-01-01", "endDate": "2025-01-31", "limit": 10 }
Response: { "data": [{ "rank": 1, "merchantId": "M001", "merchantName": "商户A", "amount": 999999.99 }, ...] }
```

---

## 11. DataAgent 侧配置

在 DataAgent 的 `application.yml` 中配置指标模拟应用地址：

```yaml
spring:
  ai:
    alibaba:
      data-agent:
        capabilities:
          metric-system:
            enabled: true
            swagger-url: http://localhost:8081/v3/api-docs/metric
            base-url: http://localhost:8081
            route-threshold: 0.75
            refresh-interval-seconds: 1800
            timeout-ms: 10000
```

---

## 12. 验证清单

创建模拟应用后，按以下步骤验证：

1. **启动模拟应用** → 访问 `http://localhost:8081/v3/api-docs/metric` 确认返回有效 JSON
2. **启动 DataAgent** → 查看日志确认 `Metric catalog refreshed successfully`
3. **测试路由** → 在 DataAgent 对话中输入 "查询最近7天 GMV 趋势"，确认路由到指标接口
4. **检查参数** → 确认缺失必填参数时返回 `NEED_CLARIFICATION` 状态
5. **检查响应** → 确认数据行正确提取并展示为表格

---

## 13. 注意事项

- OpenAPI 文档中的 **`$ref` 引用** 必须使用 `#/components/schemas/...` 格式，解析器会自动还原引用
- **Request body** 必须使用 `content.application/json.schema` 结构
- 如果使用 GET 方法，参数应放在 `parameters` 中而非 `requestBody`
- Swagger URL **不需要鉴权**（当前解析器不发送 Authorization header）
- 指标系统不可达**不会阻止 DataAgent 启动**，仅记录 warning 日志
