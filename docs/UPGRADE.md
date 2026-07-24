# 升级说明

DataAgent 不在应用启动时自动迁移旧数据库。仓库 SQL 文件是新建数据库的结构基线，不是版本化 migration runner。

## 1. 新库与旧库

- 新建本地库：可以手工执行 `data-agent-management/src/main/resources/sql/schema.sql`，按需执行 `data.sql` 示例数据。
- 已有数据库：禁止通过 `DATA_AGENT_DATASOURCE_SQL_INIT=always` 代替升级；`CREATE TABLE IF NOT EXISTS` 不会补齐已有表字段和索引。
- H2 测试：使用 `application-h2.yml` 和 `src/main/resources/sql/h2/` 下的测试基线，不代表 MySQL 已兼容。

真实 MySQL 行为是数据库变更的主要判据。

## 2. 标准升级流程

1. 阅读目标版本 Release Notes、本文和相关专题文档中的升级章节。
2. 停止写入或进入维护窗口。
3. 备份 MySQL、不可重建的上传数据和环境配置。
4. 在同版本副本或测试库执行目标 DDL/DML。
5. 对照 `src/main/resources/sql/schema.sql` 检查表、字段、索引和约束。
6. 使用目标版本启动，确认 Spring 容器、MySQL、当前选定的向量库和模型依赖正常。
7. 执行核心 REST、SSE、历史会话和管理页面回归。
8. 观察稳定后再恢复写入。

升级期间保持：

```dotenv
DATA_AGENT_DATASOURCE_SQL_INIT='never'
```

## 3. 当前专项升级记录

### 指标目录与本地修正

引入 `metric_local_config`、指标本地名称/描述/别名和上下架状态的旧库，需要执行 [指标目录与检索说明](METRIC_CATALOG_RETRIEVAL.md#8-旧库手工升级) 中的 DDL，并在升级后手工触发一次 OpenAPI 同步。

完整表定义已经进入 MySQL schema 基线，但不会自动应用到旧表。

### ES/PGVector 轻量部署基线

- `docker-compose-es.yml` 已从开发者机器绝对 bind mount 改为 Docker 命名卷。原绝对目录中的 ES 数据不会自动复制到新卷；需要保留旧挂载的环境应维护外部 Compose 覆盖，或先通过 snapshot/restore 迁移，不能直接把新空卷当作原索引。
- PGVector 新基线要求 `id text PRIMARY KEY`、与模型一致的 `vector(1024)`、余弦 HNSW、metadata GIN 索引和 pgvector 0.8+ 迭代扫描。旧表若使用 UUID 主键或其他维度，应新建表并重灌向量数据，不建议原地强改已有 embedding 列。
- `docker-entrypoint-initdb.d` 只在空数据目录首次执行。已有 PG Volume 必须在维护窗口手工执行审核后的升级 SQL。

## 4. 回滚准备

数据库变更提交前必须判断是否可向后兼容：

- 新增可空字段通常可先升级数据库再升级应用。
- 删除、改名、收紧非空约束和改变字段类型需要单独的数据迁移与回滚方案。
- Elasticsearch Mapping 或维度变化通常需要新索引或重建，不能只回滚应用包。
- PGVector 扩展、文本主键、向量维度或索引变化必须先执行受控升级 SQL；初始化脚本不会在已有 Volume 上重跑。
- Prompt、工具结果和消息持久化格式变化需要验证旧历史消息仍可展示。

如果升级失败，优先恢复上一版本应用和升级前数据库备份。不要在未确认影响范围时反复执行 `schema.sql` 或 `data.sql`。

## 5. 新版本维护要求

凡是修改 MySQL schema 的提交，至少需要：

- 同步生产 MySQL 与测试 SQL 基线。
- 说明旧库需要执行的增量 DDL/DML。
- 给出验证查询或针对性测试。
- 说明数据是否可回滚、是否需要重建当前向量库文档。
- 在本文件增加对应版本或功能的升级记录。

目前仓库尚未建立完整版本化 CHANGELOG；在该机制建立前，发布负责人必须从 Git 差异和本文件确认数据库变更，不能只依赖 `docs/todolist.md` 的完成记录。
