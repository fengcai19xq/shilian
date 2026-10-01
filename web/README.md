# web —— PC 三栏工作台

对应任务：T07 T13
设计依据：《航锂智能体 PC 客户端交互设计 v1.0》的 5 个场景

左栏智能体与历史 / 中栏对话与任务卡 / 右栏引用溯源与产物预览。

## 必须实现的三条规则

1. 企业知识回答的每个数字可点角标，右栏定位到原文页码
2. 库内未命中时如实告知并二次确认，**不得静默改用通用模型**
3. 模型选择器分组展示，不合规的置灰并写明原因；自动切换合规通道必须显式提示

## 如何本地跑

技术栈：React 18 + TypeScript + Vite，测试用 vitest + testing-library，样式用 CSS Modules，不引入 UI 组件库。

```bash
cd web
corepack enable            # 首次使用，按 package.json 的 packageManager 自动使用 pnpm 9
pnpm install
pnpm dev                   # http://localhost:5173
pnpm test                  # vitest 单元测试
pnpm typecheck             # TypeScript 类型检查
pnpm build                 # 产物输出到 dist/
```

后端接口全部走 `src/api/mock.ts` 的写死示例数据（已脱敏），不连真实后端。可用以下问题体验五种消息态：

| 输入示例 | 消息态 |
| --- | --- |
| 最近三年研发费用占营收比例 | 企业知识命中（点角标看右栏溯源） |
| 公司食堂菜单 | 库内未命中 → 二次确认后才走通用模型 |
| 高管薪酬方案 | 权限拒答（不显示越权文档名） |
| 帮我按中信银行清单成册 | 清单成册任务卡 |

## 目录

```text
src/api/          接口类型（对齐 contracts/）与 mock 实现，经 ApiContext 注入，测试可替换
src/state/        消息模型与工作台状态（会话、右栏、按会话记忆的模型选择）
src/components/   三栏组件：LeftSidebar / ChatPane / RightPane / Composer / ModelSelector / messages/*
src/__tests__/    vitest 用例
```
