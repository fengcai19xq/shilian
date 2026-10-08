# matcher（Java）—— 清单解析与三态匹配

Python 版 `services/matcher/` 的 Spring Boot 重写，对应任务 T10 / T17。
接口契约：`contracts/matching.md`（只读），JSON 字段一律 snake_case。

## 如何本地跑

需要 JDK 17 + Maven 3.9，在本目录下独立构建与测试，不依赖其它模块：

```bash
cd services-java/matcher
mvn -q test                 # 单元测试（全部使用内存 fake，不访问真实系统）
mvn spring-boot:run         # 本地起服务，默认端口 8010（MATCHER_PORT 可改）
```

默认装配的是 `NullCandidateProvider` / `RejectAllDecideProvider`：所有清单项落 `missing`，不会产生假阳性。
接入真实依赖时提供自己的 `CandidateProvider` / `DecideProvider` Bean（经 HTTP 调 retrieval-proxy / decide）即可替换。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/matcher/checklists` | 上传并解析清单：multipart `file`（.xlsx/.xlsm/文本）或表单 `text`，可选 `title`；201 |
| POST | `/matcher/checklists/{id}/run` | 触发匹配（后台执行），返回 `{task_id, checklist_id, state}`；202 |
| GET | `/matcher/tasks/{task_id}` | `{task, progress}` |
| POST | `/matcher/items/{id}/confirm` | 人工确认 `{doc_ids, note}`，操作人取请求头 `X-User-Id`（缺失 401） |
| GET | `/matcher/checklists/{id}/manifest` | 导出给 packager 的 manifest，可选 `title` / `watermark` |

错误体与 Python 版一致：`{"detail": "..."}`；解析失败 422、资源不存在 404。

## 目录

```text
src/main/resources/data/   types.yaml（资料类型词典）/ columns.yaml（Excel 列头配置），复制自 Python 版
config/    ScoringConfig（权重与阈值，可读环境变量）/ ParserConfig / MatcherJson（snake_case）
core/      ChecklistParser（POI / 文本）、ConstraintExtractor（正则 + 代码，不调模型）、
           HardConstraints（任一不通过即 rejected）、Scoring（三态打分）、TypeDictionary
port/      CandidateProvider / DecideProvider 可注入接口 + 安全默认实现
service/   MatcherService（编排、审计、manifest）、MemoryStore（内存存储）
web/       MatcherController
```

## 配置

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `MATCHER_MATCHED_THRESHOLD` | 0.85 | ≥ 该值为 matched |
| `MATCHER_PENDING_THRESHOLD` | 0.50 | ≥ 该值为 pending，否则 missing |
| `MATCHER_W_RECALL` / `MATCHER_W_DECIDE` / `MATCHER_W_FORM` | 0.35 / 0.50 / 0.15 | 打分权重 |

调参方向以降低假阳性为准（宁可调高 matched 阈值）。

## 测试

`services/matcher/tests` 的 43 条 pytest 用例逐条移植为 JUnit 5（`ApiTest` 13、`ParsingTest` 9、`ScoringTest` 21，
参数化用例按条计数），另加 1 条 Spring 上下文装配用例 `MatcherApplicationTests`。
