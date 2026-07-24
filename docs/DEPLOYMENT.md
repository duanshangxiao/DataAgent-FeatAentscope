# 部署说明

本文说明当前项目可支持的部署边界和运行要求。`docker-file/` 下的 Compose 与 Dockerfile 是开发示例，不是经过安全加固的生产部署方案。

## 1. 部署边界

当前 DataAgent：

- 没有完整身份认证和多租户数据隔离。
- Controller 存在宽松 CORS 配置。
- API Key 页面提供生命周期管理，但后端请求鉴权尚未形成完整发布门禁。
- 模型和数据源凭据仍有待统一加密治理。
- SSE 当前使用 GET，请求问题和人工反馈可能进入 URL 与访问日志。

因此只能部署在本机或可信内网，并由外层网关、VPN 或其他可信访问控制保护。不要直接暴露到公网或不可信共享网络。

## 2. 运行依赖

| 组件 | 当前基线 | 作用 |
|---|---|---|
| Java | 17 | 后端运行时 |
| MySQL | 推荐 8.0 | 管理库，保存 Agent、配置、会话和知识数据 |
| Elasticsearch | 8.18.0 | 默认向量与关键词检索 |
| PGVector | PostgreSQL 16；镜像示例 `pgvector/pgvector:pg16-bookworm` | 可选纯向量检索后端，与 ES 二选一 |
| Node.js | 18+ | 前端构建；生产运行由 Nginx 等静态服务器承载 |
| 模型服务 | OpenAI 兼容或项目支持的供应商 | Chat Model 与 Embedding Model |
| Python/Docker | 可选 | Python 分析执行器 |

业务数据源由用户在管理页面配置，不应与管理库混淆。

## 3. 推荐启动顺序

1. 启动并验证 MySQL。
2. 按[升级说明](UPGRADE.md)创建或对齐管理库结构。
3. 启动选定的向量库：ES 至少达到 yellow；或 PGVector 通过 `pg_isready` 且初始化表校验成功。
4. 注入环境变量或挂载外部配置，启动后端。
5. 在管理页面配置 Chat Model 和 Embedding Model。
6. 构建并发布前端，反向代理 `/api`、`/nl2sql` 和 `/uploads` 到后端。
7. 验证普通 REST、SSE 流式问答、历史会话和上传访问。

启用或切换第三方指标目录时，按
[第三方指标目录切换与运维配置手册](METRIC_CATALOG_OPERATIONS.md)
完成目录预检、配置注入、滚动发布、状态确认和回滚准备。

## 4. 源码方式运行

在仓库根目录加载配置并启动后端：

```bash
set -a
source .env
set +a

./mvnw -pl data-agent-management spring-boot:run
```

构建后端制品：

```bash
./mvnw -pl data-agent-management -am clean package
```

构建前端：

```bash
cd data-agent-frontend
npm ci
npm run build
```

前端产物位于 `data-agent-frontend/dist`。

## 5. Docker 示例的限制

`docker-file/docker-compose.yml` 会启动管理库、后端、前端和两个示例业务数据源，但当前仍需注意：

- 示例中包含固定的本地密码，只能用于本机演示。
- 默认向量库是 Elasticsearch，而主 Compose 没有包含向量库服务。
- ES 和 PGVector 分别使用 `docker-compose-es.yml`、`docker-compose-pgvector.yml` 独立启动，并加入同一个 `data-agent-network`。
- 后端容器需要将 `SPRING_ELASTICSEARCH_URIS` 指向容器网络中的 Elasticsearch，而不是默认 `localhost`。
- PG 模式下后端需要将 `DATA_AGENT_VECTORSTORE_URL` 指向 `pgvector:5432`，并设置 `DATA_AGENT_VECTORSTORE_TYPE=pgvector`。
- Compose 将 SQL 初始化设为 `always`，不适用于已有库或生产环境。
- 未配置生产级 TLS、认证、Secret、备份和资源治理。

本地启动示例：

```bash
docker compose -f docker-file/docker-compose-es.yml up -d
docker compose -f docker-file/docker-compose.yml up --build
```

PGVector 模式示例：

```bash
docker compose -f docker-file/docker-compose-pgvector.yml up -d

export DATA_AGENT_VECTORSTORE_TYPE=pgvector
export DATA_AGENT_VECTORSTORE_URL='jdbc:postgresql://pgvector:5432/vector_db_feat?currentSchema=public&ApplicationName=data-agent-vectorstore&tcpKeepAlive=true&connectTimeout=10&socketTimeout=30'
export DATA_AGENT_VECTORSTORE_USERNAME=data_agent
export DATA_AGENT_VECTORSTORE_PASSWORD='由 Secret 注入的密码'

docker compose -f docker-file/docker-compose.yml up --build
```

主 Compose 已显式把这些变量传入后端容器；只在宿主机创建 `.env` 但不在 Compose `environment` 中声明的其他变量，不会自动进入容器。

PGVector 初始化脚本只在空数据目录首次启动时运行。已有 Volume 必须在维护窗口手工执行经过审核的升级 SQL；不要通过删除 Volume 的方式“触发初始化”。

在团队公共环境使用前，应先复制并维护一份环境专属 Compose/Helm/部署清单，不要直接复用示例中的密码和绝对路径。

## 6. 反向代理要求

SSE 代理必须：

- 关闭响应缓冲和缓存。
- 使用 HTTP/1.1 或支持流式响应的更高版本。
- 设置足够长的读取超时。
- 客户端断开时及时向后端传播连接关闭。
- 避免记录完整查询字符串，当前 SSE GET 参数可能包含用户问题。

仓库中的 `docker-file/config/nginx.conf` 可作为流式代理行为参考，但不能替代生产安全配置。

## 7. 持久化与备份

至少持久化并备份：

- MySQL 管理库。
- 当前启用的 ES 或 PGVector 数据，以及可重新生成的数据源与重建步骤。
- 使用本地文件存储时的上传目录。
- 环境专属配置和 Secret 的安全副本。

升级前必须同时备份 MySQL 和不可重建的上传数据。指标目录可以从 OpenAPI 重建，但本地指标修正和上下架状态保存在 MySQL，不能只备份 Elasticsearch。

## 8. 运行检查

当前项目没有完整 Actuator 健康端点。可使用以下分层检查：

- 后端进程：日志出现 `Started DataAgentApplication`。
- REST：`GET /v3/api-docs` 返回成功。
- Elasticsearch：`GET /_cluster/health` 至少为 yellow。
- PGVector：`pg_isready` 成功，`vector` 扩展、目标表、1024 维列和 HNSW 索引存在。
- 模型：管理页面的模型连接测试成功。
- 业务闭环：创建会话后完成一次 SSE 问答，并刷新页面确认历史可见。
- 指标能力：启用时确认目录已就绪，并执行一次检索验证。

仅验证端口可访问，不能证明模型、向量库和业务数据源都已可用。

## 9. 上线前检查

- `DATA_AGENT_DATASOURCE_SQL_INIT=never`。
- 所有默认密码已替换，密钥没有进入镜像和 Git。
- 服务位于可信网络边界内，外层已有访问控制。
- CORS、反向代理日志和上传大小符合目标环境要求。
- MySQL、当前向量库、上传目录已有备份与恢复演练。
- SSE 超时、客户端断开、取消和模型超时经过验证。
- 外部指标系统不可用时，数据库降级路径符合预期。
- 指标能力启用时，目录 parser、完整目录 URL 和业务 base URL 已按运维手册核对。
