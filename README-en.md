<div align="center">
  <p><a href="./README.md">中文</a> | English</p>
  <h1>Spring AI Alibaba DataAgent</h1>
  <p>
    <strong>Enterprise-grade Intelligent Data Analyst powered by <a href="https://github.com/alibaba/spring-ai-alibaba" target="_blank">Spring AI Alibaba</a></strong>
  </p>
  <p>
     Text-to-SQL | Python Deep Analysis | Intelligent Reports | MCP Server | RAG Enhancement
  </p>

  <p>
    <a href="https://github.com/alibaba/spring-ai-alibaba"><img src="https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.0.0-blue" alt="Spring AI Alibaba"></a>
    <img src="https://img.shields.io/badge/Spring%20Boot-3.4.8+-green" alt="Spring Boot">
    <img src="https://img.shields.io/badge/Java-17+-orange" alt="Java">
    <img src="https://img.shields.io/badge/License-Apache%202.0-red" alt="License">
    <a href="https://deepwiki.com/spring-ai-alibaba/DataAgent"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  </p>

   <p>
    <a href="#-introduction">Introduction</a> •
    <a href="#-core-features">Core Features</a> •
    <a href="#-metric-capability-maintenance-notes">Metric Capability</a> •
    <a href="#-quick-start">Quick Start</a> •
    <a href="#-documentation">Documentation</a> •
    <a href="#-community--contribution">Community</a>
  </p>
</div>

<br/>

<div align="center">
    <img src="img/LOGO.png" alt="DataAgent" width="1807" style="border-radius: 10px; box-shadow: 0 4px 8px rgba(0,0,0,0.1);">
</div>

<br/>

## Introduction

**DataAgent** is an enterprise-grade intelligent data analysis Agent built on **Spring AI Alibaba Graph**. It goes beyond traditional Text-to-SQL tools, evolving into an AI-powered data analyst capable of executing **Python deep analysis** and generating **multi-dimensional chart reports**.

The system adopts a highly extensible architecture design, **fully compatible with OpenAI API specifications** for chat models and embedding models, and supports **flexible integration with any vector database**. Whether for private deployment or integration with mainstream LLM services (such as Qwen, Deepseek), it can be easily adapted to provide flexible and controllable data insight services for enterprises.

Additionally, this project natively supports **MCP (Model Context Protocol)**, enabling seamless integration as an MCP server into MCP-compatible ecosystem tools such as Claude Desktop.

## Core Features

| Feature | Description |
| :--- | :--- |
| **Intelligent Data Analysis** | StateGraph-based Text-to-SQL conversion, supporting complex multi-table queries and multi-turn conversation intent understanding. |
| **Python Deep Analysis** | Built-in Docker/Local Python executor, automatically generating and executing Python code for statistical analysis and machine learning predictions. |
| **Intelligent Report Generation** | Analysis results are automatically summarized into HTML/Markdown reports with ECharts visualizations, WYSIWYG. |
| **Human Feedback Mechanism** | Innovative Human-in-the-loop mechanism, supporting user intervention and adjustments during the plan generation phase. |
| **RAG Retrieval Enhancement** | Integrated vector database, supporting semantic retrieval of business metadata and terminology libraries to improve SQL generation accuracy. |
| **Multi-Model Orchestration** | Built-in model registry, supporting runtime dynamic switching between different LLM and Embedding models. |
| **Metric Capability Routing** | Routes standard metric questions to a dedicated metric system instead of recalculating business metrics with SQL. |
| **MCP Server** | Compliant with MCP protocol, supporting external provision of NL2SQL and agent management capabilities as a Tool Server. |
| **API Key Management** | Comprehensive API Key lifecycle management with fine-grained permission control. |

## Project Structure

![dataagent-structure](img/dataagent-structure.png)


## Quick Start

> For detailed installation and configuration guide, please refer to [Quick Start Guide](docs/QUICK_START.md).

### 1. Prerequisites
- JDK 17+
- MySQL 5.7+
- Node.js 16+

### 2. Start Services

```bash
# 1. Import database
mysql -u root -p < data-agent-management/src/main/resources/sql/schema.sql

# 2. Start backend
cd data-agent-management
./mvnw spring-boot:run

# 3. Start frontend
cd data-agent-frontend
npm install && npm run dev
```

### 3. Access the System
Open your browser and visit `http://localhost:3000` to start creating your first data agent!

## Metric Capability Maintenance Notes

### Why this capability exists

The project now includes an optional metric capability so that standard business metrics can be queried from an external metric system instead of being recomputed from SQL every time. This helps maintain consistent metric definitions, reduces prompt misuse of database tools, and prepares the runtime for mixed questions such as "query GMV first, then explain details from the warehouse".

The current implementation covers phase 1 to phase 4 of the design plan: capability abstraction, Swagger synchronization, metric tools, runtime routing, skill toggle, and explain tracing. `MIXED` is already recognized at routing and prompt-instruction level, but not yet implemented as a full multi-step orchestrator.

### How to enable it

The capability is disabled by default. It becomes active only when all of the following are true:

1. `spring.ai.alibaba.data-agent.capabilities.metric-system.enabled=true`
2. Both endpoints are configured:
   - `DATA_AGENT_METRIC_SWAGGER_URL`
   - `DATA_AGENT_METRIC_BASE_URL`
3. The Agent enables the built-in skill `builtin-metric-system`

### Main code locations

| File | Responsibility |
| :--- | :--- |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/CapabilityProvider.java` | Common capability abstraction |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/CapabilityRoutingService.java` | Route decision, tool filtering, runtime instructions |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/capability/metric/MetricCapabilityProvider.java` | Main metric capability implementation |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/service/impl/AiAgentRuntimeServiceImpl.java` | Runtime entry that invokes capability routing |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/agentscope/runtime/AgentRuntimeExtensionFactory.java` | Injects routing instructions into runtime extensions |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/skill/impl/LocalSkillServiceImpl.java` | Registers `builtin-metric-system` |
| `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/observability/AnswerTraceExplainStore.java` | Stores routing and metric tool summaries for explain |
| `docs/duan/data-agent-metric-capability-implementation-plan.md` | Detailed design and staged delivery plan |

### Maintenance tips

1. When changing Swagger parsing, always update parser and catalog-search tests together.
2. When changing routing keywords or thresholds, verify all `METRIC_ONLY / DB_ONLY / MIXED / UNKNOWN` paths.
3. When adding metric tools, also update skill copy, runtime instructions, and explain tracing.
4. When changing HTTP request/response contracts, review metric request building and result normalization together.
5. The current metric implementation is intentionally concentrated in one file for low-risk delivery. If the feature keeps growing, split it into `openapi / catalog / execution / tool / model`.
6. The module uses package-level JaCoCo checks. Capability changes usually require tests, otherwise `install` may fail because of coverage gates.

## Documentation

| Document | Contents |
| :--- | :--- |
| [Quick Start](docs/QUICK_START.md) | Environment requirements, database import, basic configuration, getting started |
| [Architecture Design](docs/ARCHITECTURE.md) | System layered architecture, StateGraph and workflow design, core module sequence diagrams |
| [Developer Guide](docs/DEVELOPER_GUIDE.md) | Development environment setup, detailed configuration manual, coding standards, extension development (vector DB/models) |
| [Advanced Features](docs/ADVANCED_FEATURES.md) | API Key invocation, MCP server configuration, custom hybrid retrieval strategies, Python executor configuration |
| [Knowledge Configuration Best Practices](docs/KNOWLEDGE_USAGE.md) | Explanation and usage of semantic models, business knowledge, and agent knowledge |
| [Metric Capability Plan](docs/duan/data-agent-metric-capability-implementation-plan.md) | Background, staged implementation plan, technical design, and testing requirements for the metric capability |

## Community & Contribution

- **DingTalk Group**: `154405001431` ("DataAgent User Group 1") Some users may not be able to join due to account security issues. If possible, please try with a different account.
- **Contribution Guide**: Community contributions are welcome! Please refer to the [Developer Guide](docs/DEVELOPER_GUIDE.md) to learn how to submit PRs.

## License

This project is licensed under the Apache License 2.0.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=spring-ai-alibaba/DataAgent&type=Date)](https://star-history.com/#spring-ai-alibaba/DataAgent&Date)

## Contributors

<a href="https://github.com/spring-ai-alibaba/DataAgent/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=spring-ai-alibaba/DataAgent" />
</a>

---

<div align="center">
    Made with ❤️ by Spring AI Alibaba DataAgent Team
</div>
