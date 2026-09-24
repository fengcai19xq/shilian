# 航锂智能体（shilian）

企业级知识智能体：多部门文档统一入库、按权限问答与溯源、融资清单自动成册、企微与 PC 双端。

底座为 **Dify（编排）+ RAGFlow（知识加工与检索）**，模型层为 **大模型生成 + Jev 决策** 双层。

## 目录

```text
contracts/                 跨模块接口契约（唯一约定来源，改动需评审）
services/retrieval-proxy/  权限过滤与检索转发
services/matcher/          清单解析与三态匹配
services/packager/         成册执行器（纯函数，沙箱内运行）
services/gateway/          模型网关 / 企微 / NL2SQL
services/decide/           decide() 路由与 Jev 接入
web/                       PC 三栏工作台
docs/progress.html         开发进度看板（由 tasks.json 生成）
tasks.json                 任务与依赖的单一数据源
scripts/build_board.py     生成看板
```

## 进度看板

```bash
python3 scripts/build_board.py    # 生成 docs/progress.html，浏览器直接打开
```

状态可在看板里直接改（存浏览器本地），点「导出 tasks.json」回写仓库即完成同步。

## 并行开发约定

每个模块由一个开发者或一个子会话负责，**只读写自己的模块目录**，`contracts/` 只读。跨模块需求先改契约、再改实现。这样既避免冲突，也把每个会话需要读的代码压到最小。

## 四条红线

1. 权限过滤在检索之前完成，越权文档不得以任何形式泄露存在性
2. 库内未命中必须如实告知，禁止静默转通用大模型编答案
3. L4 数据不出 VPC；L3 调用前脱敏；模型自动切换必须显式提示用户
4. 资料包生成后仍需人工终审并登记外发，系统不自动对外发送
