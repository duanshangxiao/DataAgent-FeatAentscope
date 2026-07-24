# 快速开始

本指南目标是让开发者从空环境跑通一次本地问答。详细配置见[配置参考](CONFIGURATION.md)，生产或共享环境见[部署说明](DEPLOYMENT.md)。

## 1. 环境要求

| 依赖 | 版本/建议 |
|---|---|
| JDK | 17 |
| Node.js | 18+ |
| MySQL | 推荐 8.0 |
| Elasticsearch | 8.18.0，默认向量存储 |
| PGVector | 可选；镜像示例 `pgvector/pgvector:pg16-bookworm` |
| Maven | 使用仓库根目录 `./mvnw` |

Python 和 Docker 只在使用相应代码执行器或本地容器示例时需要。

## 2. 准备管理库

创建一个本地 MySQL 数据库，然后在仓库根目录执行 schema：

```bash
mysql -h 127.0.0.1 -P 3360 -u root -p \
  feat-agentscope < data-agent-management/src/main/resources/sql/schema.sql
```

如果需要示例 Agent 和知识数据，再单独执行：

```bash
mysql -h 127.0.0.1 -P 3360 -u root -p \
  feat-agentscope < data-agent-management/src/main/resources/sql/data.sql
```

管理库保存 DataAgent 自身的 Agent、模型、会话和知识配置。用户实际查询的业务数据源需要在系统启动后通过管理页面另行添加。

旧数据库不能只执行完整 schema 代替升级，请先阅读[升级说明](UPGRADE.md)。

## 3. 准备 Elasticsearch

默认配置连接 `http://localhost:9200`。仓库提供本地开发示例：

```bash
docker compose -f docker-file/docker-compose-es.yml up -d
```

该 Compose 使用命名卷持久化数据，HTTP 端口默认只绑定本机。确认服务可用：

```bash
curl -s http://localhost:9200/_cluster/health
```

详细设置、IK 中文分词插件和磁盘水位线见 [Elasticsearch 集成说明](ELASTICSEARCH.md)。

## 4. 创建本地配置

```bash
cp .env.example .env
```

编辑 `.env`，至少填写：

```dotenv
DATA_AGENT_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3360/feat-agentscope?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai'
DATA_AGENT_DATASOURCE_USERNAME='root'
DATA_AGENT_DATASOURCE_PASSWORD='你的本地密码'
DATA_AGENT_DATASOURCE_SQL_INIT='never'
DATA_AGENT_VECTORSTORE_TYPE='elasticsearch'
SPRING_ELASTICSEARCH_URIS='http://127.0.0.1:9200'
```

`.env` 已被 Git 忽略，`.env.example` 只保存无密钥模板。Spring Boot 不会自动读取 `.env`，下一步必须加载它。

## 5. 启动后端

在仓库根目录执行：

```bash
set -a
source .env
set +a

./mvnw -pl data-agent-management spring-boot:run
```

后端启动成功应看到 `Started DataAgentApplication`。随后访问：

- `http://localhost:8065/v3/api-docs`
- `http://localhost:8065/swagger-ui.html`

如果使用 IntelliJ，运行 `DataAgentApplication` 前需要打开 `Run` → `Edit Configurations...`，在当前 Spring Boot/Application 配置的 `Environment variables` 中选择根目录 `.env`；只把文件放在项目根目录不会自动生效。完整步骤见[配置参考](CONFIGURATION.md#intellij-idea-加载方式)。

## 6. 启动前端

另开终端：

```bash
cd data-agent-frontend
npm ci
npm run dev
```

访问 `http://localhost:3000`。Vite 默认代理到 `http://localhost:8065`，需要修改时在启动前设置：

```bash
export VITE_BACKEND_TARGET='http://127.0.0.1:8065'
```

## 7. 配置模型并完成首次问答

1. 打开“模型配置”，新增 Chat Model 和 Embedding Model。
2. 使用页面连接测试确认模型地址、模型名和密钥有效。
3. 新建 Agent，保持默认 `commonagent` 语义。
4. 添加业务数据源并限制允许访问的表和字段。
5. 按需配置语义模型、业务知识和 Agent 知识。
6. 创建会话并完成一次问答。
7. 刷新页面，确认历史消息仍能正确展示。

如果使用指标系统，还需要配置指标 OpenAPI 地址并为 Agent 启用 `builtin-metric-system` skill，详见[配置参考](CONFIGURATION.md#5-指标系统)。

## 8. 常见问题

### 数据库密码仍为空

通常是只创建了 `.env`，但没有 `source .env`，或者 IDE 没有导入环境变量。

### 表不存在

默认 `DATA_AGENT_DATASOURCE_SQL_INIT=never`，应用不会偷偷创建或修改数据库。新库应手工执行 schema。

### Elasticsearch 连接失败

确认 9200 端口、容器健康状态、IK 插件兼容性和 `SPRING_ELASTICSEARCH_URIS`。容器中的 `localhost` 指向容器自身，不是宿主机或另一个 ES 容器。

### 后端启动但无法问答

分别检查 Chat Model、Embedding Model、当前选择的 ES/PGVector 和目标业务数据源。HTTP 端口可访问不代表这些外部依赖已就绪。

### 修改配置后没有生效

确认变量已导出到后端进程，并检查是否被更高优先级的启动参数或外部 `application.yml` 覆盖。

## 9. 下一步

- 开发和验证：[开发者指南](DEVELOPER_GUIDE.md)
- 全部环境变量：[配置参考](CONFIGURATION.md)
- 生产边界：[部署说明](DEPLOYMENT.md)
- 知识配置：[知识配置最佳实践](KNOWLEDGE_USAGE.md)
