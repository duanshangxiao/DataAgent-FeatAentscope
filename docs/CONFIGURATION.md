# 配置参考

本文是 DataAgent 配置的统一入口。配置事实以 `data-agent-management/src/main/resources/application.yml`、配置属性类和前端 `vite.config.js` 为准；其他文档不重复维护完整配置表。

## 1. 配置来源与优先级

后端遵循 Spring Boot 配置优先级。常见来源从低到高为：

1. 仓库中的 `application.yml` 默认值。
2. 外部 `application.yml`。
3. 操作系统环境变量。
4. 启动命令参数。

真实密码、Access Key 和 Secret Key 不得写入仓库配置。开发环境使用被 Git 忽略的根目录 `.env`；CI 和部署环境使用流水线或部署平台的 Secret。

Spring Boot 不会自动加载 `.env`。命令行使用前需要导出：

```bash
set -a
source .env
set +a
```

### IntelliJ IDEA 加载方式

IDEA 直接运行 `DataAgentApplication` 时不会自动扫描项目根目录 `.env`，需要为对应的 Run Configuration 显式导入：

1. 打开 `Run` → `Edit Configurations...`。
2. 选择用于启动 `DataAgentApplication` 的 Spring Boot 或 Application 配置；临时配置建议先保存为永久配置。
3. 找到 `Environment variables`。如果当前界面没有显示，使用 `Modify options` 将该字段加入配置。
4. 点击该字段旁的文件浏览按钮，选择仓库根目录 `.env`。新版 IDEA 支持直接选择 `.env` 文件或环境脚本。
5. 点击 `Apply`，完全停止旧进程后重新 Run/Debug。

每个 Run Configuration 的环境相互独立：Spring Boot、Maven Test 和前端 npm 配置不会自动共享同一个 `.env`。需要在哪个配置中使用，就在哪个配置中导入；或者先在终端 `source .env`，再从该终端执行对应命令。

如果 IDEA 版本没有 `.env` 文件浏览入口，可以点击环境变量编辑按钮，手工添加 `DATA_AGENT_DATASOURCE_URL`、`DATA_AGENT_DATASOURCE_USERNAME`、`DATA_AGENT_DATASOURCE_PASSWORD` 和 `DATA_AGENT_DATASOURCE_SQL_INIT`。不建议为了本地便利把真实密码写回 `application.yml`。

## 2. 本地 `.env`

从模板创建本地文件：

```bash
cp .env.example .env
```

最小配置如下：

```dotenv
DATA_AGENT_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3360/feat-agentscope?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai'
DATA_AGENT_DATASOURCE_USERNAME='root'
DATA_AGENT_DATASOURCE_PASSWORD='本地密码'
DATA_AGENT_DATASOURCE_SQL_INIT='never'
```

JDBC URL 建议使用引号包裹，避免命令行加载时把 `&` 等字符解释为 Shell 运算符。

## 3. 数据库变量

这里的管理库用于保存 Agent、会话、配置和知识数据，不是 Agent 查询分析的业务数据源。

| 环境变量 | 默认值 | 用途 | 建议 |
|---|---|---|---|
| `DATA_AGENT_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3360/feat-agentscope...` | 管理库 JDBC URL | 开发和部署必须明确配置 |
| `DATA_AGENT_DATASOURCE_USERNAME` | `root` | 管理库用户名 | 生产使用最小权限账号 |
| `DATA_AGENT_DATASOURCE_PASSWORD` | 空 | 管理库密码 | 必须由外部环境提供 |
| `DATA_AGENT_DATASOURCE_SQL_INIT` | `never` | Spring SQL 初始化模式 | 正常开发和生产保持 `never` |

`DATA_AGENT_DATASOURCE_SQL_INIT` 支持 Spring Boot 的 `never`、`always` 和 `embedded`：

- `never`：不执行 `sql/schema.sql` 和 `sql/data.sql`，是已有数据库的默认选择。
- `always`：每次启动都先执行 schema，再执行示例数据；只用于可丢弃的全新本地库。
- `embedded`：只对 H2 等嵌入式数据库初始化，对 MySQL 不执行。

`schema.sql` 中的 `CREATE TABLE IF NOT EXISTS` 不能修改已有表，`continue-on-error=true` 还会让部分 SQL 失败后继续启动。因此 `always` 不是数据库迁移方案，旧库升级必须遵循[升级说明](UPGRADE.md)。`h2` Profile 在 `application-h2.yml` 中固定为 `always`，不受该环境变量控制。

## 4. Elasticsearch 与向量存储

当前默认向量存储是 Elasticsearch，默认地址为 `http://localhost:9200`、索引名为 `spring-ai-document-index`、维度为 `1024`。

Spring Boot 标准环境变量可覆盖地址：

```dotenv
SPRING_ELASTICSEARCH_URIS='http://127.0.0.1:9200'
```

PGVector 配置目前作为可选方案保留：

| 环境变量 | 用途 |
|---|---|
| `DATA_AGENT_VECTORSTORE_URL` | PGVector JDBC URL |
| `DATA_AGENT_VECTORSTORE_USERNAME` | PGVector 用户名 |
| `DATA_AGENT_VECTORSTORE_PASSWORD` | PGVector 密码 |

只有将 `spring.ai.vectorstore.type` 切换为 `pgvector` 并启用对应配置后，上述三个变量才生效。切换方法见 [Elasticsearch 集成说明](ELASTICSEARCH.md)。

## 5. 指标系统

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `DATA_AGENT_METRIC_SWAGGER_URL` | `http://127.0.0.1:8081/v3/api-docs/metric` | 指标系统 OpenAPI 地址 |
| `DATA_AGENT_METRIC_BASE_URL` | `http://127.0.0.1:8081` | 指标业务接口基础地址 |

当前 `application.yml` 将指标 capability 全局开关设为 `true`，但具体 Agent 只有绑定 `builtin-metric-system` skill 后才会启用指标工具。可通过 Spring Boot 松散绑定在部署环境关闭全局开关：

```dotenv
SPRING_AI_ALIBABA_DATA_AGENT_CAPABILITIES_METRIC_SYSTEM_ENABLED='false'
```

## 6. 文件存储与 OSS

默认使用本地 `uploads` 目录。切换 `spring.ai.alibaba.data-agent.file.type=oss` 后，需要提供：

| 环境变量 | 敏感 | 用途 |
|---|---|---|
| `OSS_ACCESS_KEY_ID` | 是 | OSS Access Key ID |
| `OSS_ACCESS_KEY_SECRET` | 是 | OSS Access Key Secret |
| `OSS_ENDPOINT` | 否 | OSS Endpoint |
| `OSS_BUCKET_NAME` | 否 | Bucket 名称 |
| `OSS_CUSTOM_DOMAIN` | 否 | 可选自定义访问域名 |

本地存储部署时必须持久化上传目录；OSS 密钥不得进入日志、镜像或前端变量。

## 7. Langfuse 与 AgentScope tracing

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `LANGFUSE_ENABLED` | `false` | 启用 Langfuse/OpenTelemetry 导出 |
| `LANGFUSE_HOST` | 空 | Langfuse 地址 |
| `LANGFUSE_PUBLIC_KEY` | 空 | Langfuse Public Key |
| `LANGFUSE_SECRET_KEY` | 空 | Langfuse Secret Key |
| `AGENTSCOPE_OBSERVABILITY_ENABLED` | `true` | 启用 AgentScope 原生 tracing |
| `AGENTSCOPE_OBSERVABILITY_USE_LANGFUSE_TRACER` | `true` | 优先复用 Langfuse tracer |

开启 Langfuse 时四个 `LANGFUSE_*` 变量应成组配置。未启用 Langfuse 时，AgentScope tracing 开关不等于数据一定会被导出到外部系统。

## 8. 前端与 Docker Compose

| 环境变量 | 默认值 | 生效位置 |
|---|---|---|
| `VITE_BACKEND_TARGET` | `http://localhost:8065` | Vite 开发代理目标 |
| `AI_DASHSCOPE_API_KEY` | 无 | `docker-file/docker-compose.yml` 传入后端容器 |

当前 `vite.config.js` 读取的是进程环境变量。若希望使用根目录 `.env`，应先通过 `source .env` 导出，再启动 `npm run dev`。

任何 `VITE_*` 变量都可能进入前端构建产物，禁止存放数据库密码、模型密钥和 OSS Secret。

## 9. Spring Boot 通用覆盖

没有专用变量名的配置仍可按 Spring Boot 松散绑定规则覆盖，例如：

```dotenv
SERVER_PORT='8065'
SPRING_ELASTICSEARCH_URIS='http://elasticsearch:9200'
SPRING_AI_ALIBABA_DATA_AGENT_CODE_EXECUTOR_CODE_POOL_EXECUTOR='docker'
```

部署中如果需要大量覆盖，优先使用受控的外部 `application.yml`；敏感值仍通过 Secret 环境变量注入。

## 10. 安全检查

- `.env` 必须保持 Git 忽略；只提交 `.env.example`。
- 如果密钥曾进入 Git，即使随后删除文件也必须轮换密钥。
- 不在日志、Issue、截图、测试快照中输出连接串和密钥。
- 本地、CI、测试和生产使用不同账号与数据库。
- `DATA_AGENT_DATASOURCE_SQL_INIT=always` 不得用于共享或生产数据库。
