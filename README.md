<div align="center">
  <p>中文 | <a href="./README-en.md">English</a></p>
  <h1>Spring AI Alibaba DataAgent</h1>
  <p>
    <strong>基于 <a href="https://github.com/alibaba/spring-ai-alibaba" target="_blank">Spring AI Alibaba</a> 的企业级智能数据分析师</strong>
  </p>
  <p>
     Text-to-SQL | Python 深度分析 | 智能报告 | MCP 服务器 | RAG 增强
  </p>

  <p>
    <a href="https://github.com/alibaba/spring-ai-alibaba"><img src="https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.0.0-blue" alt="Spring AI Alibaba"></a>
    <img src="https://img.shields.io/badge/Spring%20Boot-3.4.8+-green" alt="Spring Boot">
    <img src="https://img.shields.io/badge/Java-17+-orange" alt="Java">
    <img src="https://img.shields.io/badge/License-Apache%202.0-red" alt="License">
    <a href="https://deepwiki.com/spring-ai-alibaba/DataAgent"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  </p>

   <p>
    <a href="#-项目简介">项目简介</a> • 
    <a href="#-核心特性">核心特性</a> • 
    <a href="#-指标能力实现与维护说明">指标能力说明</a> • 
    <a href="#-快速开始">快速开始</a> • 
    <a href="#-文档导航">文档导航</a> • 
    <a href="#-加入社区--贡献">加入社区</a>
  </p>
</div>

<br/>

<div align="center">
    <img src="img/LOGO.png" alt="DataAgent" width="1807" style="border-radius: 10px; box-shadow: 0 4px 8px rgba(0,0,0,0.1);">
</div>

<br/>

## 📖 项目简介

**DataAgent** 是一个基于 **Spring AI Alibaba Graph** 打造的企业级智能数据分析 Agent。它超越了传统的 Text-to-SQL 工具，进化为一个能够执行 **Python 深度分析**、生成 **多维度图表报告** 的 AI 智能数据分析师。

系统采用高度可扩展的架构设计，**全面兼容 OpenAI 接口规范**的对话模型与 Embedding 模型，并支持**灵活挂载任意向量数据库**。无论是私有化部署还是接入主流大模型服务（如 Qwen, Deepseek），都能轻松适配，为企业提供灵活、可控的数据洞察服务。

同时，本项目原生支持 **MCP (Model Context Protocol)**，可作为 MCP 服务器无缝集成到 Claude Desktop 等支持 MCP 的生态工具中。

## ✨ 核心特性

| 特性 | 说明 |
| :--- | :--- |
| **智能数据分析** | 基于 AgentScope ReActAgent 与动态工具的 Text-to-SQL 分析，支持受约束的多表查询和多轮会话。 |
| **Python 深度分析** | 内置 Docker/Local Python 执行器，自动生成并执行 Python 代码进行统计分析与机器学习预测。 |
| **智能报告生成** | 分析结果自动汇总为包含 ECharts 图表的 HTML/Markdown 报告，所见即所得。 |
| **人工反馈机制** | 独创的 Human-in-the-loop 机制，支持用户在计划生成阶段进行干预和调整。 |
| **RAG 检索增强** | 集成向量数据库，支持对业务元数据、术语库的语义检索，提升 SQL生成准确率。 |
| **多模型调度** | 内置模型注册表，支持运行时动态切换不同的 LLM 和 Embedding 模型。 |
| **指标能力路由** | 支持将标准指标问题路由到独立指标系统，避免通过 SQL 重算已有业务指标。 |
| **MCP 服务器** | 遵循 MCP 协议，支持作为 Tool Server 对外提供 NL2SQL 和 智能体管理能力。 |
| **API Key 管理** | 完善的 API Key 生命周期管理，支持细粒度的权限控制。 |

## 🏗️ 项目结构

![dataagent-structure](img/dataagent-structure.png)


## 🚀 快速开始

> 详细的安装和配置指南请参考 [📑 快速开始文档](docs/QUICK_START.md)。

### 1. 准备环境
- JDK 17+
- MySQL 5.7+
- Node.js 16+

### 2. 启动服务

```bash
# 1. 导入数据库
mysql -u root -p < data-agent-management/src/main/resources/sql/schema.sql

# 2. 启动后端
cd data-agent-management
./mvnw spring-boot:run

# 3. 启动前端
cd data-agent-frontend
npm install && npm run dev
```

### 3. 访问系统
打开浏览器访问 `http://localhost:3000`，开始创建您的第一个数据智能体！

## 📊 指标能力实现与维护说明

### 需求背景

为了避免标准业务指标完全依赖数据库 SQL 重新计算，系统新增了一个可选的“指标 capability”。当用户问题命中标准指标语义时，Agent 会优先调用外部指标系统，而不是直接暴露数据库工具给模型。这样做的目标有三点：

1. 保证标准指标口径统一，减少 SQL 重算带来的口径偏差。
2. 将“标准指标查询”与“数据库明细分析”分流，降低模型误用工具的概率。
3. 为后续混合问题编排打基础，例如“先查 GMV，再补充明细原因分析”。

当前实现已将业务指标语义与 OpenAPI 调用契约拆分：指标检索只处理名称、编码、别名和业务描述，确定指标后再通过绑定关系读取 HTTP 契约。指标管理页支持本地语义修正、上下架，以及与真实问数共用同一个后端方法的检索验证。`MIXED` 类型已经具备路由与提示约束，但尚未扩展成复杂的多阶段执行编排器。

### 启用方式

指标能力默认关闭。需要同时满足以下条件才会生效：

1. 在 `data-agent-management/src/main/resources/application.yml` 中开启 `spring.ai.alibaba.data-agent.capabilities.metric-system.enabled=true`，或通过环境变量覆盖。
2. 配置指标系统 Swagger 地址和业务接口基础地址：
   - `DATA_AGENT_METRIC_SWAGGER_URL`
   - `DATA_AGENT_METRIC_BASE_URL`
3. 在 Agent 的 skill 配置中启用内置 skill `builtin-metric-system`。

推荐配置示例：

```yaml
spring:
  ai:
    alibaba:
      data-agent:
        capabilities:
          metric-system:
            enabled: true
            swagger-url: ${DATA_AGENT_METRIC_SWAGGER_URL:}
            base-url: ${DATA_AGENT_METRIC_BASE_URL:}
            refresh-interval-seconds: 1800
            route-threshold: 0.75
            timeout-ms: 10000
```

### 核心实现位置

下列文件是后续维护指标能力时最常需要查看的入口：

| 文件 | 作用 |
| :--- | :--- |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/CapabilityProvider.java` | capability 通用扩展接口 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/CapabilityRoutingService.java` | 路由判定、工具裁剪、运行时指令生成 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/metric/MetricOpenApiSyncService.java` | 拉取 OpenAPI、生成新目录 generation 并原子切换 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/metric/MetricRetrievalService.java` | 问数工具与管理页共用的指标检索入口 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/metric/MetricQueryExecutionService.java` | 根据指标绑定读取 API 契约并执行请求 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/MetricCapabilityController.java` | 指标列表、修正、上下架、检索验证和同步接口 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java` | 在运行时接入 capability 路由 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentRuntimeExtensionFactory.java` | 将路由生成的 runtime instructions 注入 Agent 执行上下文 |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/skill/impl/LocalSkillServiceImpl.java` | 注册内置 skill `builtin-metric-system` |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/observability/AnswerTraceExplainStore.java` | 记录路由结果、指标目录检索、指标查询摘要 |
| `docs/METRIC_CATALOG_RETRIEVAL.md` | 当前指标/API拆分、检索、本地修正、上下架和升级方案 |

### 运行链路

当前指标能力的执行链路如下：

1. 系统启动后，`MetricOpenApiSyncService` 拉取 Swagger/OpenAPI，将 `MetricDefinition`、`MetricApiContract` 和 `MetricBinding` 分开解析，并构建新的目录 generation。
2. 用户发起提问后，`AiAgentRuntimeServiceImpl` 会在主执行前调用 `CapabilityRoutingService`。
3. 若判定为 `METRIC_ONLY`，运行时只保留指标相关工具；若为 `DB_ONLY`，则移除指标工具。
4. `metric.catalog.search` 与指标管理页共同调用 `MetricRetrievalService`；确定上架指标后，`describe/execute` 再读取绑定的 API 契约。
5. 指标工具调用结果会被写入 `AnswerTraceExplainStore`，供 explain 查询与故障排查使用。

### 维护建议

后续如果需要扩展或修改该能力，建议遵循以下原则：

1. **改 Swagger 解析逻辑时**：优先补 `MetricOpenApiParser` 和目录搜索相关测试，避免不同 OpenAPI 方言导致目录丢失。
2. **改检索阈值或融合规则时**：必须同时验证管理页搜索和问数工具，二者不能出现不同候选顺序；阈值应由标注问题集调优。
3. **新增指标工具时**：同时更新 skill 文案、runtime instructions、explain 记录，否则模型虽然能看到工具，但不会稳定使用。
4. **改接口请求/响应结构时**：优先审查 `MetricQueryExecutionService` 的请求构造与结果归一化逻辑，避免调用成功但模型拿不到结构化结果。
5. **改上下架时**：同时检查 ES 过滤、公共检索二次过滤和执行硬门禁；OpenAPI 刷新不能重置本地状态。
6. **执行构建时**：项目启用了 JaCoCo 包级覆盖率校验，涉及 capability 的修改需要补单测，否则 `install` 可能因覆盖率不足失败。

### 推荐测试关注点

每次修改指标能力后，至少回归以下内容：

1. Swagger 解析是否仍能正确生成指标目录。
2. 指标目录检索是否能稳定返回 Top 命中项。
3. 路由是否会正确裁剪数据库工具和指标工具。
4. 指标系统异常时，返回给模型的错误是否清晰可解释。
5. explain 接口中是否能看到路由摘要、指标目录检索和指标执行步骤。
6. 下架指标是否无法通过搜索、描述或直接编码执行，管理页检索是否与问数结果一致。

## 📚 文档导航

| 文档 | 此文档包含的内容 |
| :--- | :--- |
| [快速开始](docs/QUICK_START.md) | 环境要求、数据库导入、基础配置、系统初体验 |
| [架构设计](docs/ARCHITECTURE.md) | 当前 ReActAgent 主链路、能力路由、动态工具和数据存储设计 |
| [开发者指南](docs/DEVELOPER_GUIDE.md) | 开发环境搭建、详细配置手册、代码规范、扩展开发(向量库/模型) |
| [高级功能](docs/ADVANCED_FEATURES.md) | API Key 调用、MCP 服务器配置、自定义混合检索策略、Python执行器配置 |
| [知识配置最佳实践](docs/KNOWLEDGE_USAGE.md) | 语义模型，业务知识，智能体知识的解释和使用 |
| [指标能力实施方案](docs/duan/data-agent-metric-capability-implementation-plan.md) | 指标 capability 的需求背景、分阶段实施计划、技术方案与测试要求 |

## 🤝 加入社区 & 贡献

- **钉钉交流群**: `154405001431` ("DataAgent用户1群") 部分用户可能因为账号安全问题无法加入，条件允许的情况下可换账号申请。
- **贡献指南**: 欢迎社区贡献！请查阅 [开发者文档](docs/DEVELOPER_GUIDE.md) 了解如何提交 PR。

## 📄 许可证

本项目采用 Apache License 2.0 许可证。
## Star 历史

[![Star History Chart](https://api.star-history.com/svg?repos=spring-ai-alibaba/DataAgent&type=Date)](https://star-history.com/#spring-ai-alibaba/DataAgent&Date)

## 贡献者名单

<a href="https://github.com/spring-ai-alibaba/DataAgent/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=spring-ai-alibaba/DataAgent" />
</a>

---

<div align="center">
    Made with ❤️ by Spring AI Alibaba DataAgent Team
</div>
