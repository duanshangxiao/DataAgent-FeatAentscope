# 教训日志

> 每次发现并修复一个 bug、踩到一个坑、或者碰到一个非直觉的设计约束后，在这里追加一条。
> 格式：`## 日期: 一句话标题` → 现象 → 根因 → 修复/规避方案

---

## 2025-07: OpenAPI 解析器不识别 `*/*` media type

- **现象**：MetricCapabilityProvider 启动后 `definitions` 始终为空，指标目录永远无法就绪
- **根因**：OpenAPI 解析器只匹配 `application/json` 类型的响应，第三方指标系统返回的 content-type 为 `*/*`，解析器直接跳过
- **修复**：详见 `docs/duan/data-agent-metric-capability-fix-prompt.md`
- **教训**：集成外部 OpenAPI/Swagger 时，不能假设对方遵守 content-type 约定，需要做兼容解析

---

## 模板

```markdown
## YYYY-MM-DD: 简短标题

- **现象**：用户/系统看到了什么异常行为
- **根因**：为什么发生
- **修复/规避**：最终方案，附相关 commit/文件路径
- **教训**：以后可以避免的一类问题
```
