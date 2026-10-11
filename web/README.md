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

### 接口模式

通过 `VITE_API_MODE` 切换，两种实现都满足 `WorkbenchApi` 接口：

- `mock`（默认）：走 `src/api/mock.ts` 的写死示例数据（已脱敏），不连后端
- `http`：走 `src/api/http.ts`，请求 `/api/*`，由 Vite dev proxy 转发到三个服务

```bash
cp .env.example .env.local   # 填写 VITE_API_MODE=http 与三个服务地址；.env.local 不入库
pnpm dev
```

| 环境变量 | 作用 |
| --- | --- |
| `VITE_API_MODE` | `mock` / `http` |
| `VITE_API_BASE` | 前端请求前缀，默认 `/api` |
| `DECIDE_URL` | dev proxy：`/api/decide/*` → `{DECIDE_URL}/decide/*` |
| `RETRIEVAL_URL` | dev proxy：`/api/retrieval/*` → `{RETRIEVAL_URL}/retrieval/*` |
| `GATEWAY_URL` | dev proxy：`/api/gateway/*` → `{GATEWAY_URL}/*`（即 `/v1/invoke`） |
| `VITE_USER_ID` | 本地联调的 `X-User-Id` |
| `VITE_DEV_PRINCIPAL` | 本地联调的权限主体 JSON；生产由网关按会话态注入 |

### 对话主链路

提问 → `POST /decide/route` →

- `enterprise`：`POST /retrieval/search`（`mode=full`、`top_k=8`、`score_threshold=0.35`，带 principal 与 `X-User-Id`）→ 取 top 6 片段组织 prompt → `POST /v1/invoke` → 渲染回答，角标 `[n]` 点击后右栏定位第 n 个片段
- `kb_empty`，或 `enterprise` 但 `total=0`：提示「知识库中没有依据」，用户二次确认后才走通用模型
- `general`：直接 `POST /v1/invoke`，`sensitivity=L1`，不带 principal 与公司数据
- `scope_desc == "（无可检索范围）"`（retrieval 返回或 decide evidence）：权限提示
- 网关返回 `switched_reason` 时在回答上方提示切换原因、实际通道（`channel`）与实际模型

mock 模式下可用以下问题体验各消息态：

| 输入示例 | 消息态 |
| --- | --- |
| 最近三年研发费用占营收比例 | 企业知识命中（点角标看右栏溯源；选公有云模型可看到 switched_reason 提示） |
| 公司食堂菜单 | 库内未命中 → 二次确认后才走通用模型 |
| 高管薪酬方案 | 权限提示（不显示任何文档信息） |
| 帮我按中信银行清单成册 | 清单成册任务卡（仅 mock） |
| 其他问题 | 通用问答 |

## 目录

```text
src/api/          接口类型（对齐 contracts/）、mock 与 http 实现、模型目录，经 ApiContext 注入，测试可替换
src/state/        消息模型、工作台状态（路由主链路、右栏、按会话记忆的模型选择）、prompt 组织与角标解析
src/components/   三栏组件：LeftSidebar / ChatPane / RightPane / Composer / ModelSelector / messages/*
src/__tests__/    vitest 用例（*.http.test.tsx 用 fetch mock 驱动真实 http 客户端）
```
