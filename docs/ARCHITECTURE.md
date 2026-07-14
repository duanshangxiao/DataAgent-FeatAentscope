# 项目架构说明

> Spring AI Alibaba DataAgent — 基于 Spring AI + AgentScope 的智能数据分析 Agent 平台

## 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| **语言** | Java | 17 |
| **框架** | Spring Boot | 3.4.8 |
| **Web 层** | Spring WebFlux (Reactive) | 3.4.8 |
| **ORM** | MyBatis + Spring Boot Starter | 3.0.4 |
| **业务数据库** | MySQL (Druid 连接池) | 8.0 / Druid 1.2.22 |
| **向量库** | PostgreSQL + PGVector (HikariCP) | 42.4.1 |
| **搜索引擎** | Elasticsearch | 8.18.0 |
| **LLM 接入** | Spring AI + DashScope SDK | 1.1.0 / 2.15.1 |
| **Agent 框架** | AgentScope | 1.0.11 |
| **API 文档** | springdoc-openapi (Swagger) | 2.8.8 |
| **可观测性** | OpenTelemetry → Langfuse | 1.32.0 |
| **Python 执行** | Docker Java / 本地进程 | 3.5.3 |
| **SQL 解析** | JSQLParser | 4.9 |
| **构建工具** | Maven | 3.x |
| **前端** | Vue 3 + Vite 5 + TypeScript + Element Plus | — |

## 模块划分

本项目为 **单模块 Maven 工程**，后端核心代码均在 `data-agent-management` 模块内按包分层：

```
spring-ai-alibaba-data-agent  (root pom, packaging=pom)
├── data-agent-management      (Spring Boot 可执行 jar, 所有后端代码)
└── data-agent-frontend        (Vue 3 前端, NPM 工程, 非 Maven 模块)
```

## 包结构约定（data-agent-management 模块）

```
com.alibaba.cloud.ai.dataagent
├── DataAgentApplication.java      # @SpringBootApplication 启动类 (端口 8065)
│
├── controller/                     # Controller 层 (15 个)
│   ├── AgentController             # Agent CRUD、API Key 管理
│   ├── ChatController              # 对话交互 (SSE 流式)
│   ├── DataAgentController         # 核心 NL2SQL 图谱分析引擎入口
│   ├── DatasourceController        # 数据源管理
│   ├── SemanticModelController     # 语义模型 (字段别名)
│   ├── BusinessKnowledgeController # 业务知识库
│   ├── AgentKnowledgeController    # Agent 知识管理
│   ├── SkillController             # 本地技能管理
│   ├── AgentSkillController        # Agent-技能绑定
│   ├── AgentDatasourceController   # Agent-数据源绑定
│   ├── AgentPresetQuestionController # 预置问题
│   ├── FileUploadController        # 文件上传
│   ├── ModelConfigController       # 模型配置
│   ├── SessionEventController      # SSE 会话事件
│   └── GlobalExceptionHandler      # 全局异常处理
│
├── service/                        # Service 层 (16 个子包)
│   ├── agent/                      # Agent 管理、启动初始化
│   ├── aimodelconfig/              # LLM 模型注册、动态切换 (AiModelRegistry)
│   ├── business/                   # 业务知识库
│   ├── chat/                       # 对话消息 & 会话管理
│   ├── code/                       # Python 代码执行 (本地/Docker/AI模拟)
│   ├── datasource/                 # 数据源连接管理 (含 DDL/SQL 执行)
│   ├── file/                       # 文件存储 (本地/OSS)
│   ├── hybrid/                     # 混合检索 (向量+关键词, 融合策略)
│   ├── knowledge/                  # Agent 知识向量化、领域知识搜索
│   ├── langfuse/                   # Langfuse 可观测上报
│   ├── llm/                        # LLM 调用 (流式/阻塞)
│   ├── mcp/                        # MCP Server 服务
│   ├── schema/                     # 数据库 Schema 元数据
│   ├── semantic/                   # 语义模型 Excel 导入导出
│   ├── skill/                      # Agent-技能绑定、本地技能
│   └── vectorstore/                # 向量存储服务
│
├── agentscope/                     # AgentScope 集成层
│   ├── runtime/                    # Agent 运行时 (事件、Hook、适配器)
│   ├── service/                    # AgentScope 模型工厂 & 会话
│   ├── session/                    # 会话注册 & MySQL 会话持久化
│   ├── template/                   # 通用 Agent 模板 (CommonAgent)
│   └── tool/                       # Agent 工具目录 (数据源/知识/语义/SQL安全/技能)
│
├── entity/                         # 实体类 (13 个)
│   ├── Agent                       ├── AgentDatasource
│   ├── AgentKnowledge              ├── ChatSession / ChatMessage
│   ├── Datasource                  ├── SemanticModel
│   ├── BusinessKnowledge           ├── ModelConfig
│   ├── LogicalRelation             └── ...
│
├── mapper/                         # MyBatis Mapper (14 个)
│
├── dto/                            # 数据传输对象
├── vo/                             # 视图对象 (ApiResponse、PageResponse 等)
├── bo/                             # 业务对象 (SchemaInfo、ColumnInfo 等)
├── enums/                          # 枚举 (10 个: DatabaseDialect、KnowledgeType、ErrorCode 等)
│
├── config/                         # 配置类 (7 个)
│   ├── DataAgentConfiguration      # 核心配置: 异步、向量存储、文本分割器、模型代理
│   ├── DatasourceConfig            # 双数据源配置 (业务DB + PGVector DB)
│   ├── OpenTelemetryConfig         # OTLP/Langfuse 可观测
│   ├── McpServerConfig             # MCP Server 工具注册
│   ├── OpenApiConfig               # Swagger/OpenAPI
│   ├── WebConfig                   # 静态资源映射
│   └── AgentScopeTracingConfiguration
│
├── properties/                     # @ConfigurationProperties (8 个)
│   ├── DataAgentProperties         # 核心配置 (spring.ai.alibaba.data-agent.*)
│   ├── FileStorageProperties       # 文件存储
│   ├── CodeExecutorProperties      # Python 代码执行
│   ├── AgentSkillProperties        # Agent 技能
│   ├── PgVectorDatasourceProperties # PGVector 数据源
│   ├── MetricCapabilityProperties  # 指标系统能力
│   ├── OssStorageProperties        # OSS 存储
│   └── AgentScopeObservabilityProperties
│
├── connector/                      # 多数据库连接器抽象
│   ├── accessor/                   # 数据访问器工厂 (7 种数据库)
│   ├── ddl/                        # DDL 工厂
│   ├── impls/                      # 具体实现 (mysql/h2/postgre/oracle/sqlserver/hive/dameng)
│   ├── pool/                       # 连接池管理
│   ├── SqlExecutor.java
│   └── ResultSetBuilder.java
│
├── prompt/                         # Prompt 模板加载
├── event/                          # 事件 (Agent知识变更)
├── exception/                      # 异常定义 (InternalServerException、InvalidInputException)
├── constant/                       # 常量
├── annotation/                     # 自定义注解 (@InEnum、@McpServerTool)
├── converter/                      # 对象转换器
├── observability/                  # 会话追踪存储
├── splitter/                       # 文本分割器 (段落/语义/句子)
├── strategy/                       # Token 计数、批处理策略
├── capability/                     # 插件式能力路由 (指标系统)
│   └── metric/                     # 指标系统能力实现
│
└── util/                           # 工具类 (12 个)
    ├── SqlUtil                     ├── JsonUtil
    ├── ChatResponseUtil            ├── MarkdownParserUtil
    ├── DocumentConverterUtil       ├── ApiKeyUtil
    ├── McpServerToolUtil           └── ...
```

## 核心架构设计

### NL2SQL 分析工作流 (StateGraph)

系统基于 **LangGraph 风格的 StateGraph** 实现端到端的自然语言数据分析流水线：

```
用户输入 → 意图识别 → 证据召回 → 查询增强 → Schema 召回 → 表关系分析
       → 可行性评估 → 计划生成 → (可选: 人工审核) → SQL 生成 → SQL 执行
       → Python 代码生成 → Python 执行 → 分析报告生成 → SSE 流式输出
```

### 数据存储设计

| 用途 | 数据库 | 说明 |
|------|--------|------|
| **业务数据** | MySQL (Druid) | Agent 配置、知识库、对话记录、数据源元信息 |
| **向量存储** | PostgreSQL + PGVector (HikariCP) | 知识文档向量化，维度 1024，余弦距离 |
| **数据源** | MySQL / PostgreSQL / Oracle / SQL Server / Hive / Dameng / H2 | Agent 分析对象的业务数据库 |
| **文件存储** | 本地磁盘 / 阿里云 OSS | 知识文件、Excel 模板、上传文件 |

### 数据库表 (12 张)

`agent`, `datasource`, `agent_datasource`, `agent_datasource_tables`, `agent_datasource_columns`, `semantic_model`, `logical_relation`, `business_knowledge`, `agent_knowledge`, `agent_skill_binding`, `chat_session`, `chat_message`, `agent_preset_question`, `model_config`

## 关键配置项

| 配置 key | 默认值 | 说明 |
|----------|--------|------|
| `server.port` | 8065 | 后端端口 |
| `DATA_AGENT_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3360/feat-agentscope` | 业务数据库 |
| `DATA_AGENT_VECTORSTORE_URL` | `jdbc:postgresql://localhost:5432/vector_db_feat` | 向量库 |
| `spring.ai.alibaba.data-agent.vector-store.table-topk-limit` | 10 | 表召回数量 |
| `spring.ai.alibaba.data-agent.vector-store.table-similarity-threshold` | 0.2 | 表相似度阈值 |
| `spring.ai.alibaba.data-agent.vector-store.default-topk-limit` | 8 | 默认召回数量 |
| `spring.ai.alibaba.data-agent.vector-store.default-similarity-threshold` | 0.4 | 默认相似度阈值 |
| `spring.ai.alibaba.data-agent.llm-service-type` | stream | LLM 调用方式 |
| `spring.ai.alibaba.data-agent.code-executor.code-pool-executor` | local | Python 执行环境 |
| `spring.ai.alibaba.data-agent.max-sql-retry-count` | 10 | SQL 最大重试次数 |
| `spring.ai.alibaba.data-agent.capabilities.metric-system.enabled` | true | 指标能力开关 |

## 环境要求

- JDK 17+
- MySQL 8.0+
- PostgreSQL 14+ (含 PGVector 插件)
- (可选) Elasticsearch 8.x
- (可选) Docker (用于 Python 沙箱执行)
- Maven 3.x / Make
- Node.js 18+ (前端)

## 启动方式

```bash
# 一键启动所有依赖 (MySQL, PostgreSQL) + 后端 + 前端
make start

# 仅启动后端
cd data-agent-management && mvn spring-boot:run

# 仅启动前端
cd data-agent-frontend && npm install && npm run dev
```

## 相关文档

- `docs/QUICK_START.md` — 快速开始
- `docs/DEVELOPER_GUIDE.md` — 开发者指南
- `docs/ADVANCED_FEATURES.md` — 高级特性 (MCP Server、API 调用)
- `docs/KNOWLEDGE_USAGE.md` — 知识库使用指南
