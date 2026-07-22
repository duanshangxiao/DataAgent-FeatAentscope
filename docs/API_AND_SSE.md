# API 与 SSE 协议说明

本文描述当前对外接口入口和前后端共同依赖的流式语义。普通 REST 的完整字段以运行中 OpenAPI 为准：

- OpenAPI JSON：`GET /v3/api-docs`
- Swagger UI：`GET /swagger-ui.html`

当前接口没有完整统一认证，不应直接暴露在公网或不可信网络。

## 1. 接口分组

主要 REST 前缀包括：

| 前缀 | 作用 |
|---|---|
| `/api/agent` | Agent 配置、发布状态和 API Key 管理 |
| `/api/model-config` | Chat/Embedding 模型配置与连接测试 |
| `/api/datasource` | 业务数据源管理和元数据读取 |
| `/api/semantic-model` | 语义模型配置与导入 |
| `/api/business-knowledge` | 业务知识管理与向量刷新 |
| `/api/agent-knowledge` | Agent 知识管理 |
| `/api/metric-capability` | 指标目录、修正、上下架、检索与同步 |
| `/api/agent/{id}/sessions` | 会话列表、创建和清理 |
| `/api/sessions/{sessionId}` | 消息、trace、回答解释和会话操作 |
| `/api/stream/search` | Agent SSE 流式执行 |

不要根据本文手写全部 DTO；调用普通 REST 时优先以当前 `/v3/api-docs` 生成客户端或核对字段。

## 2. 会话与运行标识

| 标识 | 语义 |
|---|---|
| `agentId` | 当前 Agent 的数字 ID |
| `sessionId` | 前端会话 ID，也是流式请求使用的 `threadId` |
| `threadId` | AgentScope memory 与运行时会话键，默认必须等于当前 `sessionId` |
| `runtimeRequestId` | 一次问答运行的唯一标识，用于取消、trace 和回答解释 |

前端开始流式请求前，应先通过 `POST /api/agent/{id}/sessions` 创建会话。后端会验证 `threadId` 确实属于当前 `agentId`。

## 3. SSE 请求

当前接口：

```http
GET /api/stream/search
Accept: text/event-stream
```

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `agentId` | 是 | 数字字符串 |
| `threadId` | 是 | 当前会话 ID |
| `runtimeRequestId` | 建议 | 本次运行唯一 ID；取消和解释链路依赖它 |
| `query` | 是 | 用户问题 |
| `clarifyCheckEnabled` | 否 | 是否启用歧义检查，默认按 `false` 处理 |
| `humanFeedback` | 否 | 本轮是否包含人工补充 |
| `humanFeedbackContent` | 否 | 人工补充内容 |
| `rejectedPlan` | 否 | 是否拒绝上一计划 |
| `preferredCapability` | 否 | 空值自动路由；`database` 强制数据库路径；`metric-system` 优先指标路径 |

由于当前使用 GET，`query` 和 `humanFeedbackContent` 可能进入浏览器历史、代理日志和访问日志。代理层应避免记录完整查询字符串，接口改为 POST 流属于后续安全整改项。

## 4. SSE 事件

服务端发送三类事件：

| event | data | 语义 |
|---|---|---|
| `message` 或默认消息事件 | `AgentResponse` | 文本增量、推理片段、工具调用或工具结果 |
| `complete` | `AgentResponse` | 正常完成，客户端应关闭 EventSource |
| `error` | `AgentResponse` | 运行失败，`error=true`，客户端应停止等待 |

`AgentResponse` 主要字段：

```json
{
  "agentId": "1",
  "threadId": "session-id",
  "nodeName": "planner-reasoning",
  "textType": "TEXT",
  "text": "增量内容",
  "metadata": {},
  "error": false,
  "complete": false
}
```

当前后端 `textType` 包括 `TEXT`、`JSON`、`PYTHON`、`SQL`、`MARK_DOWN` 和 `RESULT_SET`。客户端必须允许新增类型，并对未知类型退化为安全文本展示。

常见 `nodeName`：

- `planner-reasoning`：模型推理或最终文本增量。
- `tool:<toolName>`：工具调用提示或工具结果。
- 其他节点名属于内部实现，客户端不应据此控制核心业务流程。

## 5. 取消与断连

当前没有独立的停止 REST 接口。前端调用 `EventSource.close()` 后，后端通过连接取消信号使用 `threadId + runtimeRequestId` 标记运行取消，并抑制取消后的 SSE、错误传播和 memory 写回。

因此：

- 每次运行都应生成新的 `runtimeRequestId`。
- 不能只复用 `threadId` 判断某次运行是否仍有效。
- 代理必须传播客户端断连，不能长期缓存或吞掉取消信号。
- 客户端收到 `complete` 或 `error` 后应立即关闭连接。

## 6. 历史消息与实时流

实时 SSE 和刷新后的历史展示是两条路径：

1. SSE 负责实时展示 AgentResponse。
2. 前端当前仍通过会话消息 REST 接口保存可见消息。
3. AgentScope 原生 memory 和回答 explain 由后端运行时维护。

修改消息类型、工具结构或渲染格式时，必须同时验证实时流、流结束后的静默状态以及刷新页面后的历史展示。结构化工具结果落库时应保存可反序列化结构，不能只保存不可恢复的 HTML。

## 7. 错误与安全

- 非数字 `agentId` 返回 400。
- 不属于当前 Agent 的 `threadId` 会被拒绝。
- 运行异常通过 `error` SSE 事件或连接错误呈现。
- 接口响应和日志不得包含数据库密码、模型密钥、完整连接串或用户敏感数据。
- API Key 管理功能不等于所有管理接口已经完成鉴权，具体边界见[高级功能](ADVANCED_FEATURES.md)和[部署说明](DEPLOYMENT.md)。
