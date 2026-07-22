# 高级功能与当前边界

本文只说明已经存在但需要额外配置或安全判断的能力。具体配置值统一见[配置参考](CONFIGURATION.md)。

## 1. API Key 管理

Agent 管理接口支持 API Key 的生成、重置、删除和启用状态管理。

需要特别注意：API Key 生命周期管理不等于所有对外请求已经完成统一鉴权。当前项目仍缺少完整身份认证、多租户隔离和企业级权限门禁，因此不能仅凭“已生成 API Key”就把服务直接暴露到公网。

远程部署前至少需要：

- 在可信网关或后端入口验证调用身份。
- 校验 API Key 与目标 Agent、状态和权限的关系。
- 对管理接口和问答接口分别授权。
- 对密钥进行掩码返回、轮换、审计和限流。

安全边界见[部署说明](DEPLOYMENT.md)和根目录 `SECURITY.md`。

## 2. MCP Server

后端引入了 Spring AI MCP Server WebFlux starter，并通过 `McpServerConfig` 注册 MCP 工具。MCP Tool 与 Agent 内部 ToolCallback 会做隔离，避免把对外 MCP 工具重复装入 AgentScope 工具集。

MCP 端点、传输方式和客户端兼容性受当前 Spring AI 版本及环境配置影响。接入时应以运行日志和实际 starter 配置为准，并完成一次 MCP Inspector 或目标客户端的端到端验证，不要只根据历史文档中的固定路径判断。

当前 MCP 接口同样没有完整生产鉴权，必须放在可信网络或受保护网关之后。

## 3. Python 代码执行器

配置前缀：

```yaml
spring.ai.alibaba.data-agent.code-executor
```

代码层支持 `DOCKER`、`CONTAINERD`、`KATA`、`AI_SIMULATION` 和 `LOCAL` 枚举。当前 `application.yml` 默认显式选择 `local`。

- `local`：直接使用后端机器的 Python，开发方便，但隔离最弱，不建议处理不可信输入。
- `docker`：使用容器隔离，可限制网络、CPU、内存和超时；目标环境需要 Docker Daemon。
- `ai-simulation`：不真实执行 Python，只模拟结果，适合部分演示和无运行环境场景。
- `containerd/kata`：代码保留了扩展枚举，使用前必须确认对应实现和目标环境已经完整验证。

生产环境不要让 `local` 执行器运行不可信模型生成代码。容器执行器也要保持无网络或最小网络权限、只读基础镜像、资源限制和短超时。

## 4. 文件存储

项目支持本地文件和阿里云 OSS：

- `local`：默认写入后端工作目录下的 `uploads`，部署时需要持久化并由反向代理转发 `/uploads`。
- `oss`：需要配置 OSS Endpoint、Bucket 和 Access Key；Secret 只能从部署环境注入。

切换存储实现后需要验证上传、数据库回填、页面刷新和旧文件访问，不能只验证上传接口返回成功。

## 5. Langfuse 与回答解释

Langfuse 默认关闭。启用后，通过 OpenTelemetry 导出模型和 AgentScope tracing。运行中还会维护会话 trace 和回答 explain 数据，供前端查看路由、检索与工具执行摘要。

可观测数据可能包含用户问题、模型输出和工具摘要。接入外部 Langfuse 前必须确认数据分类、脱敏、访问控制和保留周期。

## 6. 指标 capability

指标能力从外部 OpenAPI 同步业务指标语义与执行契约，并通过 Agent skill 控制是否向当前 Agent 提供指标工具。当前公共检索同时服务运行时问答和管理页检索验证。

具体设计、上下架门禁、generation 切换和旧库升级见[指标目录与检索说明](METRIC_CATALOG_RETRIEVAL.md)。早期实现计划保存在 `docs/duan/`，仅供历史追溯。

## 7. 逻辑外键与知识增强

对于没有物理外键的业务库，可以通过逻辑关系和语义模型补充表间关系、业务字段名、同义词和业务描述。配置效果依赖实际数据源表结构和 Agent 绑定范围。

最佳实践见[知识配置说明](KNOWLEDGE_USAGE.md)。修改知识或关系后需要重新验证召回、SQL 生成、只读安全和历史问答，不应把 Prompt 提示当成唯一约束。
