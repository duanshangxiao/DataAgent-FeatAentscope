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

### 从 PGVector 切到 ES

`application.yml:26-45`：

```yaml
spring:
  ai:
    vectorstore:
      # PGVector（注释保留，可随时切回）
      # type: pgvector
      # ...

      # 切换到 Elasticsearch
      type: elasticsearch
      elasticsearch:
        index-name: spring-ai-document-index
        initialize-schema: true

  elasticsearch:
    uris: http://localhost:9200
```

启用混合检索（向量 + ES 关键词双路 RRF 融合）：

```yaml
spring.ai.alibaba.data-agent.vector-store.enable-hybrid-search: true
```

### 切回 PGVector

1. 把 `application.yml` 恢复为 `type: pgvector`，取消注释 PGVector 配置
2. ES 数据不会被删除——如果需要可手动清理

## 3. ES 容器管理

### docker-compose

配置文件：`docker-file/docker-compose-es.yml`

```bash
# 启动
docker compose -f docker-file/docker-compose-es.yml up -d

# 停止
docker compose -f docker-file/docker-compose-es.yml down

# 彻底清理（含数据卷）
docker compose -f docker-file/docker-compose-es.yml down -v
```

### 容器参数

| 配置 | 值 | 说明 |
|------|-----|------|
| 镜像 | `elasticsearch:8.18.0` | 与 `pom.xml` 客户端版本一致 |
| HTTP | `localhost:9200` | REST API |
| Transport | `localhost:9300` | 节点间通信 |
| 内存 | 512MB 堆 / 1GB 容器上限 | 开发环境 |
| 安全 | 已关闭 | `xpack.security.enabled=false` |
| 数据目录 | `~/elasticsearch/8180/data/` | 宿主机持久化 |
| 日志目录 | `~/elasticsearch/8180/logs/` | 宿主机持久化 |
| 快照目录 | `~/elasticsearch/8180/snapshots/` | 预留 |

### 磁盘水位线

本地开发磁盘空间紧张时，首次启动后需调整磁盘水位线阈值（已通过 REST API 持久化到集群状态）：

```bash
curl -X PUT localhost:9200/_cluster/settings -H 'Content-Type: application/json' \
  -d '{"persistent":{"cluster.routing.allocation.disk.watermark.low":"97%","cluster.routing.allocation.disk.watermark.high":"98%","cluster.routing.allocation.disk.watermark.flood_stage":"99%"}}'
```

此配置存储在集群状态中，随 volume 持久化。仅重建 volume 时需要重新执行。

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

切到 ES 后，**PGVector 中的历史数据不会自动迁移**。指标数据依赖 Swagger URL 重新拉取，其他数据需用户重新导入。

## 6. 混合检索

启用 `enable-hybrid-search=true` 后，检索流程变为：

```
用户查询 → AgentVectorStoreService.search()
  ├── 向量搜索: ES dense_vector (cosine 相似度)
  ├── 关键词搜索: ES match query on content 字段
  └── RRF 融合 (k=60) → 去重排序 → topK 结果
```

关键词搜索通过 `ElasticsearchHybridRetrievalStrategy` 实现：
- 获取原生 `ElasticsearchClient`
- 对 `content` 字段执行 match query
- 支持 `Filter.Expression` 过滤（如按 agentId、vectorType 筛选）
- ES 不可用时降级为空列表，融合退化为纯向量结果

## 7. 故障排查

| 现象 | 可能原因 | 解决 |
|------|----------|------|
| 启动报 `Index not found` | 索引尚未创建 | 设置 `initialize-schema: true` 或手动创建索引 |
| 集群状态 red / 分片未分配 | 磁盘空间不足 | 调整磁盘水位线（见 §3）|
| 向量检索无结果 | 数据未灌入 | 检查指标 Swagger URL 是否可达，手动触发知识导入 |
| 关键词搜索不生效 | `enable-hybrid-search=false` | 检查 `application.yml` 配置 |
| `vm.max_map_count` 过低 | Linux 内核限制 | `sudo sysctl -w vm.max_map_count=262144`（macOS 无需） |
