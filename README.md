<div align="center">
  <h1>Spring AI Alibaba DataAgent</h1>
  <p><strong>基于 Spring AI Alibaba 与 AgentScope 的智能数据分析应用</strong></p>
  <p>Text-to-SQL · 指标查询 · Python 分析 · 智能报告 · MCP · RAG</p>

  <p>
    <a href="https://github.com/alibaba/spring-ai-alibaba"><img src="https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.0.0-blue" alt="Spring AI Alibaba"></a>
    <img src="https://img.shields.io/badge/Spring%20Boot-3.4.8-green" alt="Spring Boot">
    <img src="https://img.shields.io/badge/Java-17-orange" alt="Java">
    <img src="https://img.shields.io/badge/Node.js-18%2B-green" alt="Node.js">
    <img src="https://img.shields.io/badge/License-Apache%202.0-red" alt="License">
  </p>
</div>

## 项目定位

DataAgent 是一个可独立运行的智能数据分析功能模块，采用 Spring Boot 单体后端和 Vue 3 单页前端。当前主链路由 AgentScope `ReActAgent` 编排，按 Agent 配置动态装配数据源探索、语义模型、业务知识、SQL 安全和指标查询工具。

项目面向本机或可信内网部署。当前没有完整身份认证、多租户隔离和企业级密钥管理，不能直接暴露在公网或不可信共享网络中。详细边界见[架构说明](docs/ARCHITECTURE.md)和[部署说明](docs/DEPLOYMENT.md)。

## 核心能力

| 能力 | 当前实现 |
|---|---|
| 自然语言问数 | 数据库元数据探索、语义检索、只读 SQL 校验与执行 |
| 指标查询 | 从 OpenAPI 同步指标目录，支持检索、本地语义修正、上下架和查询执行 |
| 知识增强 | 语义模型、业务知识和 Agent 知识库召回 |
| 流式会话 | WebFlux SSE、会话历史、AgentScope memory、取消和回答解释 |
| 多模型配置 | 在管理页面配置并切换 Chat Model 与 Embedding Model |
| 报告与分析 | Markdown/HTML 报告、ECharts 图表和可选 Python 执行器 |
| 扩展接口 | REST/OpenAPI 与 MCP Server |

## 技术栈

- 后端：Java 17、Spring Boot 3.4.8、WebFlux、MyBatis、MySQL
- Agent：Spring AI Alibaba 1.1.0.0、AgentScope 1.0.11
- 检索：Elasticsearch 8.18.0（默认）、PGVector（可选）、H2 测试内存向量库
- 前端：Vue 3、TypeScript、Vite 5、Element Plus

## 快速开始

准备 JDK 17、Node.js 18+、MySQL 和 Elasticsearch 后：

```bash
cp .env.example .env
# 编辑 .env，填写本地管理库连接和密码

set -a
source .env
set +a

./mvnw -pl data-agent-management spring-boot:run
```

另开一个终端启动前端：

```bash
cd data-agent-frontend
npm ci
npm run dev
```

访问：

- 前端：`http://localhost:3000`
- OpenAPI：`http://localhost:8065/v3/api-docs`
- Swagger UI：`http://localhost:8065/swagger-ui.html`

首次建库、Elasticsearch 和模型配置步骤见[快速开始](docs/QUICK_START.md)。Spring Boot 不会自动读取根目录 `.env`，命令行启动前必须导出变量，或在 IDE Run Configuration 中导入该文件。

## 文档导航

| 文档 | 用途 |
|---|---|
| [快速开始](docs/QUICK_START.md) | 从空环境到首次运行 |
| [配置参考](docs/CONFIGURATION.md) | 环境变量、`.env`、Spring 配置和密钥约束 |
| [架构说明](docs/ARCHITECTURE.md) | 当前模块、主链路、数据和产品边界 |
| [开发者指南](docs/DEVELOPER_GUIDE.md) | 开发、测试、验证和代码规范 |
| [部署说明](docs/DEPLOYMENT.md) | 依赖、启动顺序、可信边界和运行检查 |
| [升级说明](docs/UPGRADE.md) | SQL 基线、旧库手工升级和回滚准备 |
| [API 与 SSE](docs/API_AND_SSE.md) | REST 入口、流式协议和标识语义 |
| [高级功能](docs/ADVANCED_FEATURES.md) | API Key、MCP、执行器、存储和可观测性边界 |
| [Elasticsearch](docs/ELASTICSEARCH.md) | ES 本地配置、检索和故障排查 |
| [知识配置](docs/KNOWLEDGE_USAGE.md) | 语义模型、业务知识和 Agent 知识最佳实践 |
| [指标目录与检索](docs/METRIC_CATALOG_RETRIEVAL.md) | 指标目录、公共检索、本地修正和上下架 |
| [路线图](docs/todolist.md) | 当前整改计划和长期关注项 |

`docs/duan/` 保存早期 OpenCode 修复问题时形成的调查、任务书和实施记录，仅供追溯，不作为当前实现或操作说明。当前事实以代码、测试、配置和上述正式文档为准。

## 参与开发

提交改动前请阅读[贡献指南](CONTRIBUTING-zh.md)和[开发者指南](docs/DEVELOPER_GUIDE.md)。安全问题请按[安全策略](SECURITY.md)私下报告，不要在公开 Issue 中附带密钥、连接串或用户数据。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
