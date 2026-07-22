# 贡献指南

感谢参与 DataAgent 开发。开始前请阅读根目录 `AGENTS.md`、[架构说明](docs/ARCHITECTURE.md)和[开发者指南](docs/DEVELOPER_GUIDE.md)。

## 1. 开发原则

- 先定位定义、调用者、持久化落点和前端消费点，再修改代码。
- 保持最小必要改动，不夹带无关重构和大规模格式化。
- 不覆盖工作区中已有但不属于本任务的修改。
- 对外兼容、对内收敛到 `agentType=commonagent` 和 `promptType=system`。
- 不直接修改共享或生产数据库。
- 敏感配置只能通过环境变量或 Secret 注入。

## 2. 开发环境

环境准备、`.env` 和启动方式见：

- [快速开始](docs/QUICK_START.md)
- [配置参考](docs/CONFIGURATION.md)
- [开发者指南](docs/DEVELOPER_GUIDE.md)

建议从最新目标分支创建功能分支，分支名清晰表达改动目的。

## 3. 验证

Java 改动至少执行：

```bash
./mvnw -pl data-agent-management -am -DskipTests compile
```

业务行为改动应运行相关测试，必要时运行完整后端测试：

```bash
./mvnw -pl data-agent-management -am test
```

前端逻辑改动按范围执行：

```bash
cd data-agent-frontend
npm run type-check
npm run lint:check
npm run build
```

提交前还应根据范围运行 `format:check`、`unused` 和浏览器运行时验收。Spring Bean、配置或代理关系变化需要验证应用实际启动成功。

## 4. 数据库和文档

MySQL schema 变化必须同步生产与测试 SQL 基线，并在[升级说明](docs/UPGRADE.md)中写明旧库操作。H2 通过不代表 MySQL 兼容。

配置、API、部署、架构或开发流程发生变化时，应同步对应中文文档。项目后续只维护中文说明；不要重新引入需要人工双向同步的英文镜像。

`docs/duan/` 是早期 OpenCode 修复问题的历史记录。新事实写入正式专题文档，新的任务日志不要继续堆入该目录。

## 5. 提交与 PR

建议提交信息格式：

```text
类型(模块): 简短说明
```

例如：

```text
fix(chat): 修复取消后 memory 回写
docs(config): 补充本地环境变量说明
```

PR 应说明：

- 改了什么以及为什么。
- 影响的调用链和数据格式。
- 已执行的编译、测试、构建和运行时验证。
- 数据库升级、配置变化、兼容风险和未执行项。

安全问题请按[安全策略](SECURITY.md)私下报告，不要通过公开 PR 披露真实密钥或生产细节。
