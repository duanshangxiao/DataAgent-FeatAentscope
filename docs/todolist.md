# 重构状态 & 待办事项

> 每次完成实质性改动后更新此文件。已完成事项归档不删除，当前待办只保留未完成项。

---

## 已完成

| 日期 | 事项 | 说明 |
|------|------|------|
| 2025-07 | 指标能力 (Metric Capability) | 插件式能力路由框架 + MetricCapabilityProvider，支持 OpenAPI 元数据同步 |
| 2026-07-15 | 指标检索架构重构 — PGVector 替代自研检索 | 删除 MetricCatalogIndex 自研检索，指标元数据导入 PGVector；解析器接口化支持多格式切换；LLM 驱动的工具选择替代应用层硬路由 |
| 2026-07-15 | 指标能力跨会话故障传染 + 时间解析错误 | 修复 HTTP 失败抛异常→返回 FALLBACK_TO_DB；移除 CB OPEN 时的 ABSTAIN 路由改为保持 MIXED；runtime instructions 注入当前日期；扩展时间推断支持近X月/周/上月/本月 |
| 2026-07-15 | 时间处理重构 — 从正则推导到 LLM 驱动 | 移除复杂时间正则(近X月/周/上月/本月)，改为 LLM 驱动：代码注入当前日期锚点 + 明确指令要求 LLM 解析相对时间为显式日期；仅保留"最近N天"兜底 |
| 2026-07-15 | 修复指标工具不被优先调用的问题 | runtime instructions 改为强制覆盖模式：remove 弱条件句，用"覆盖默认路由规则+必须严格遵守"的指令要求 LLM 先调 metric.catalog.search 再考虑 DB |
| 2026-07-17 | 工具结果展示优化 — 文本块→结构化渲染 | 后端 Hook 保留 JSON 结构(TextType.JSON)；前端新增 ToolResultDisplay 组件按 toolName 分派专用渲染(datasource/sql_guard/semantic_model/knowledge/metric/skill) |
| 2026-07-20 | Elasticsearch 集成 — 向量存储后端切换支持 | 新增 ES 8.18.0 docker-compose 配置(数据持久化、资源限制、磁盘水位线)；application.yml 切换 spring.ai.vectorstore.type 从 pgvector 到 elasticsearch，PGVector 配置注释保留；启用混合检索 enable-hybrid-search；修复 CapabilityRoutingServiceTest 编译错误(route 方法签名变更) |
| 2026-07-20 | 修复暗色模式下页面标题不可见 + 标题区域样式 | AgentDetail 页 el-header/el-aside/el-main 硬编码 background-color: white 在暗色模式下与继承的白色文字(id=color var(--text-primary))冲突，白底白字完全不可见；修复方案：三处行内 white 改为 CSS 类 detail-panel，显式设置 color 以对抗暗色模式文字继承；AgentList 页 .content-header 添加白色卡片样式(背景/边框/阴影)；index.html 去掉泛型 <title> 占位符 |
| 2026-07-20 | 技能配置与能力路由解耦优化 | 修复静默降级：用户选「指标查询」但技能未绑定时，后端不再静默回退 DB_ONLY，改为注入降级告知提示给 LLM；前端对话页「指标查询」radio 未绑技能时灰显 + tooltip 提示；精简 builtin-metric-system/SKILL.md 去除与 buildRuntimeInstructions 重复的行为指令 |
| 2026-07-20 | Prompt 架构重构 — 路径专用规则从基础 Prompt 分离 | commonagent.md 精简为通用规则(Human review directive)；DB 工具链 50+ 行规则移至新文件 prompts/db-path.md；CapabilityRoutingService.buildRuntimeInstructions() 按路由类型注入对应路径 Prompt：指标可用时注入指标规则+DB 回退规则，指标不可用时注入 DB_PATH_PROMPT；移除"覆盖默认路由规则"措辞（已无默认规则可覆盖） |

---

## 当前待办

_暂无未完成项_

---

## 长期关注

- 保证所有 Agent 行为收敛到 `commonagent/system`（不扩展 agentType / scene 维度）
- SQL 基线 (`schema.sql` / `data.sql`) 与真实数据库保持一致
- `todolist.md` 作为重构状态的单一事实源
