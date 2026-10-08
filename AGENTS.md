# AGENTS.md

给自动开发会话的约束。**违反以下任意一条的实现视为不可合并。**

## 范围

- 只读写分配给你的模块目录（`services/<你的模块>/`、`services-java/<你的模块>/` 或 `web/`）
- `contracts/` 只读。需要改契约时停下来说明理由，不要直接改
- 不要改 `tasks.json` 与 `docs/progress.html` 之外的进度文件；看板由 `scripts/build_board.py` 生成

## 安全红线

1. 权限过滤必须在检索请求里完成，禁止先取结果再过滤
2. 越权文档不得出现在答案、引用、推荐或错误信息中
3. L4 数据不出 VPC；L3 调用模型前脱敏
4. 模型自动切换通道时必须返回 `switched_reason`，前端显式提示
5. 不硬编码密钥、口令、真实客户数据；测试数据一律脱敏

## 工程约定

- 日期比较、份数统计、金额计算用代码实现，不交给模型
- 模型调用一律经 `services/gateway`，模块内不直接持有密钥
- 每个对外接口写单元测试，`pytest` 可独立跑通本模块
- 提交前跑本模块的 lint 与测试

## 省 token

- 判定类任务走 Jev 或本地小模型，不用大模型
- 路由探测检索用 `mode=probe`（top_k=3、不 rerank）
- 相同 query 与文档打标结果做缓存
- 上下文按 rerank 后的 top 片段截断，不整篇塞

## Java 工程约定（业务后端：matcher / packager / 企微 / 审计 / NL2SQL）

- JDK 17 + Spring Boot 3.x + Maven，目录 `services-java/<模块>/`，每个模块独立 `pom.xml`，`mvn -q test` 可独立跑通
- PDF 用 Apache PDFBox，Excel 用 Apache POI；禁止引入未在 Maven Central 发布 ≥7 天的版本
- 接口严格对齐 `contracts/`，JSON 字段用 snake_case（Jackson `PropertyNamingStrategies.SNAKE_CASE`）
- 对应 Python 模块（`services/<模块>/tests`）的测试用例是验收基准：逐条移植为 JUnit 5，数据文件直接复用
- AI 相关薄层（retrieval-proxy / gateway / decide）保持 Python，Java 模块只通过 HTTP 调它们
