# 前端代码规范与检查

本文说明 `data-agent-frontend` 当前使用的代码质量工具和提交前检查。跨层开发流程见 `docs/DEVELOPER_GUIDE.md`。

## 1. 环境与安装

- Node.js 18+
- 依赖版本以 `package-lock.json` 为准

首次安装或 CI 使用：

```bash
cd data-agent-frontend
npm ci
```

只有明确需要更新依赖和锁文件时才使用 `npm install`。

## 2. 检查命令

| 命令 | 作用 | 是否修改文件 |
|---|---|---|
| `npm run type-check` | Vue/TypeScript 类型检查 | 否 |
| `npm run lint:check` | ESLint 检查 | 否 |
| `npm run format:check` | Prettier 格式检查 | 否 |
| `npm run unused` | unimported 与 knip 检查 | 否 |
| `npm run build` | Vite 生产构建 | 生成 `dist` |
| `npm run lint` | ESLint 自动修复 | 是 |
| `npm run format` | Prettier 批量格式化 | 是 |

普通逻辑改动至少执行：

```bash
npm run type-check
npm run lint:check
npm run build
```

提交前根据范围补充：

```bash
npm run format:check
npm run unused
```

不要在业务改动中顺手执行 `npm run lint` 或 `npm run format` 并提交大范围无关改写。需要机械格式化时应作为独立任务，先确认影响范围。

## 3. 编码约定

- Vue 组件使用 PascalCase。
- 变量和函数使用 camelCase。
- 接口类型优先使用 `interface`。
- 不新增无约束的 `any`；确实需要时应限制边界并说明原因。
- 请求参数变化需要同步 service、组件调用、保存参数、编辑回填和后端 DTO。
- 新页面检查路由、菜单、权限入口和完成后的 reload 行为。
- UI 配置必须真实落库并可正确回填，不能只改变页面状态。

当前 Prettier 规则以仓库配置为准，主要包括单引号、分号、尾随逗号、两空格缩进和 100 字符行宽。

## 4. 运行时验证

构建成功不能替代浏览器验证。以下改动必须检查真实页面：

- CSS、主题、条件渲染和交互状态。
- SSE 流式消息、工具结果和取消。
- 消息保存、刷新后的历史展示。
- 表单 disabled、checked、保存回填和 reload。

至少覆盖：

1. 流式实时阶段。
2. 流结束后的静默状态。
3. 刷新页面后的历史展示。
4. 明暗主题下的文字/背景对比度。
5. 浏览器控制台没有新增错误和警告。

## 5. 结构化消息

工具结果应保留可反序列化的原始结构，再交给 `ToolResultDisplay` 等组件渲染。不要把结构化数据先转成 HTML 后落库，否则刷新页面后无法恢复相同组件状态。

修改格式化或渲染函数前，先搜索所有调用者，分别判断实时流、消息保存和历史读取路径。
