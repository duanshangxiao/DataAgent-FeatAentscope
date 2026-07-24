# Elasticsearch 集成说明

> 当前项目默认使用 ES 作为向量存储后端并启用向量 + 关键词混合检索；PGVector 配置作为可选切换方案保留。

## 1. 架构定位

```
                    AgentVectorStoreServiceImpl  (统一入口)
                              │
                    注入 VectorStore bean (唯一，@Primary)
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
   PGVector (可选)     Elasticsearch (默认)   Simple (H2 测试)
  ──────────────────  ──────────────────────  ──────────────
  spring.ai.           spring.ai.             spring.ai.
  vectorstore.         vectorstore.           vectorstore.
  type=pgvector        type=elasticsearch     type=simple
```

两种后端**互斥**——同一时刻只有一个 `VectorStore` bean 生效，所有读写操作走同一个后端。

## 2. 切换配置

### 使用 Elasticsearch

```dotenv
DATA_AGENT_VECTORSTORE_TYPE='elasticsearch'
SPRING_ELASTICSEARCH_URIS='http://127.0.0.1:9200'
DATA_AGENT_VECTORSTORE_DIMENSIONS='1024'
DATA_AGENT_VECTORSTORE_ENABLE_HYBRID_SEARCH='true'
```

启动 ES 容器：

```bash
docker compose -f docker-file/docker-compose-es.yml up -d
```

### 使用 PGVector

PG 模式执行纯向量检索，不调用 ES 关键词分支。当前镜像基线示例为 `pgvector/pgvector:pg16-bookworm`：

```bash
docker compose -f docker-file/docker-compose-pgvector.yml up -d
```

本机启动后端时配置：

```dotenv
DATA_AGENT_VECTORSTORE_TYPE='pgvector'
DATA_AGENT_VECTORSTORE_URL='jdbc:postgresql://127.0.0.1:5432/vector_db_feat?currentSchema=public&ApplicationName=data-agent-vectorstore&tcpKeepAlive=true&connectTimeout=10&socketTimeout=30'
DATA_AGENT_VECTORSTORE_USERNAME='data_agent'
DATA_AGENT_VECTORSTORE_PASSWORD='由 Secret 注入的密码'
DATA_AGENT_VECTORSTORE_SCHEMA='public'
DATA_AGENT_VECTORSTORE_TABLE='cares_data_agent_weihai'
DATA_AGENT_VECTORSTORE_DIMENSIONS='1024'
```

PG 必须满足：

- 安装 `vector` 扩展；普通 `postgres:16` 镜像不能替代 pgvector 镜像。
- 表主键为 `text`，以兼容指标稳定文档 ID。
- 表包含 `content text`、`metadata json`、`embedding vector(1024)`。
- 余弦距离使用 `vector_cosine_ops` HNSW 索引，jsonpath 元数据过滤使用 `jsonb_path_ops` GIN 索引。
- pgvector 0.8+ 开启 HNSW 严格顺序迭代扫描，改善 generation 等过滤条件下的召回。
- 应用不自动建表；全新容器由 `docker-file/config/pgvector/01-init-vector-store.sql` 初始化。

`/docker-entrypoint-initdb.d` 只在空数据目录首次执行。已有 Volume 需要手工运行升级 SQL，不能通过重启容器更新表结构。

### 切换后的数据处理

切换配置不会复制或删除另一侧数据。指标目录会在后端启动后重新同步；业务知识、Agent 知识和 Schema 向量需要重新导入。确认新后端检索正常前保留旧后端，以便回滚。

## 3. ES 容器管理

### docker-compose

配置文件：`docker-file/docker-compose-es.yml`

```bash
# 启动
docker compose -f docker-file/docker-compose-es.yml up -d

# 停止
docker compose -f docker-file/docker-compose-es.yml down

# 停止并删除容器；当前使用 bind mount，不会删除宿主机数据
docker compose -f docker-file/docker-compose-es.yml down
```

当前 Compose 使用命名卷持久化数据和快照，HTTP 端口默认只绑定到 `127.0.0.1`。默认官方镜像不包含 IK；中文关键词检索用于生产时，应通过 `DATA_AGENT_ES_IMAGE` 指向预装与 ES `8.18.0` 完全匹配的 IK 自定义镜像，并固定镜像 digest。

### 容器参数

| 配置 | 值 | 说明 |
|------|-----|------|
| 镜像 | `elasticsearch:8.18.0` | 与 `pom.xml` 客户端版本一致 |
| HTTP | `127.0.0.1:9200` | REST API，不公开 Transport 端口 |
| 内存 | 512MB 堆 / 1280MB 容器上限 | 轻量单节点基线 |
| CPU | 2 核上限 | 应按实际检索量压测 |
| 安全 | 默认关闭 | 只能用于本机或可信内网 |
| 数据目录 | `data-agent-es-data` 命名卷 | 必须备份或准备可复现重建流程 |
| 快照目录 | `data-agent-es-snapshots` 命名卷 | 仍需注册 snapshot repository |
| 日志 | Docker `json-file`，20MB × 3 | 防止本地日志无限增长 |

### 磁盘水位线

本地开发磁盘空间紧张并出现分片只读或无法分配时，可以临时调整磁盘水位线阈值。生产环境应扩容或清理数据，不应把阈值提高到接近磁盘写满：

```bash
curl -X PUT localhost:9200/_cluster/settings -H 'Content-Type: application/json' \
  -d '{"transient":{"cluster.routing.allocation.disk.watermark.low":"90%","cluster.routing.allocation.disk.watermark.high":"92%","cluster.routing.allocation.disk.watermark.flood_stage":"95%"}}'
```

上述值只是临时排障示例，恢复空间后应清除 transient 设置并检查索引只读状态。

## 4. 查看数据

### 查看集群状态

```bash
curl -s http://localhost:9200/_cluster/health?pretty
```

### 查看索引

```bash
curl -s "http://localhost:9200/_cat/indices?v"
```

### 查看文档内容

```bash
# 搜索 5 条文档
curl -s -X GET "http://localhost:9200/spring-ai-document-index/_search" \
  -H 'Content-Type: application/json' -d '{"size": 5}' | python3 -m json.tool

# 只返回 content 和 metadata 字段
curl -s -X GET "http://localhost:9200/spring-ai-document-index/_search" \
  -H 'Content-Type: application/json' -d '{
  "size": 5,
  "_source": ["content", "metadata"]
}' | python3 -m json.tool
```

### 查看索引 mapping

```bash
curl -s "http://localhost:9200/spring-ai-document-index/_mapping?pretty"
```

### 图形化工具

推荐安装 Chrome 扩展 **Elasticvue**，连接 `http://localhost:9200` 即可浏览索引、文档和 mapping，无需 Kibana。

## 5. 数据写入

所有数据写入走 Spring AI 的 `VectorStore.add()` 抽象，切换后端后写入代码无需修改。数据来源包括：

| 来源 | 服务 | 触发方式 |
|------|------|----------|
| 指标目录 | `MetricOpenApiSyncService` | 应用启动 + 定时刷新 |
| 业务知识库 | `BusinessKnowledgeServiceImpl` | 用户操作 |
| Agent 知识 | `AgentKnowledgeResourceManager` | 用户操作 |
| Schema 元数据 | `SchemaServiceImpl` | 数据源绑定 |

ES 与 PGVector 之间的历史数据不会自动迁移。指标数据依赖 Swagger URL 重新拉取，其他数据需用户重新导入。

## 6. 混合检索

启用 `enable-hybrid-search=true` 后，检索流程变为：

```
用户查询 → AgentVectorStoreService.search()
  ├── 向量搜索: ES dense_vector (cosine 相似度)
  ├── 关键词搜索: content + 指标结构化语义字段加权
  └── RRF 融合 (k=60) → 稳定业务键去重 → 写回融合分数 → topK 结果
```

关键词搜索通过 `ElasticsearchHybridRetrievalStrategy` 实现：
- 获取原生 `ElasticsearchClient`
- 对 `content` 执行 match，并对指标 `metricCode/metricName/aliases/description` 字段加权
- 支持 `Filter.Expression` 过滤（如按 agentId、vectorType 筛选）
- 仅关键词分支执行异常时返回空列表，向量分支仍可继续；整个 ES 不可用时向量检索也会失败，不属于自动降级场景

当 `DATA_AGENT_VECTORSTORE_TYPE=pgvector` 时，策略工厂不创建 ES 关键词分支，只调用 PGVector `similaritySearch`。指标编码、名称和别名的业务层精确匹配仍然保留，但中文模糊关键词召回效果可能低于配置好 IK 的 ES 混合检索。

指标文档额外包含 `metricKey`、`generation` 和 `serviceStatus`。查询只访问当前活动 generation 的上架指标；OpenAPI 同步和本地语义修正先完整写入新 generation，激活成功后再清理旧版本，避免“先删后写”暴露空目录或半目录。指标向量文本只保存业务语义，不再混入 HTTP 路径和请求参数。

## 7. 故障排查

| 现象 | 可能原因 | 解决 |
|------|----------|------|
| 启动报 `Index not found` | 索引尚未创建 | 设置 `initialize-schema: true` 或手动创建索引 |
| 集群状态 red / 分片未分配 | 磁盘空间不足 | 调整磁盘水位线（见 §3）|
| 向量检索无结果 | 数据未灌入 | 检查指标 Swagger URL 是否可达，手动触发知识导入 |
| 关键词搜索不生效 | `enable-hybrid-search=false` | 检查 `application.yml` 配置 |
| `vm.max_map_count` 过低 | Linux 内核限制 | `sudo sysctl -w vm.max_map_count=262144`（macOS 无需） |
| PG 启动报表不存在 | 初始化脚本未执行或使用了旧 Volume | 对照初始化 SQL检查扩展、表名和 Schema，已有库手工升级 |
| PG 写入时报 UUID 格式错误 | 旧表仍使用 UUID 主键或应用未使用 TEXT ID | 将表主键与 PGVectorStore ID 类型统一为 `text/TEXT` |
| PG 报向量维度不一致 | 表、配置与 Embedding Model 维度不同 | 新建正确维度的表并重灌数据 |
