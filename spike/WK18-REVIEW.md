# WK18 契约变更评审要点与签字单

配套提案：`spike/WK18-REGISTRY-ENRICHMENT.md`（本文件是评审导读，不重复提案全文）
状态：**待签字**。签字前 `contracts/` 不改一行。
日期：2026-11-05　整理：千问工作助理（WK18）

---

## 0. 一句话背景

成册（matcher）的五类硬约束要比对候选文档的 `std_type / period / scope / copies / stamped`，但逐字段核对后确认：**这五个字段在真实链路上没有任何数据生产方**。直接接线真实召回，任何清单项都会判「属性缺失 → 不通过」，全部落 missing/rejected，成册闭环跑不通。

这是**历史欠账，不是 WeKnora 切换引入的回归**：Python 版 matcher 的检索召回从未实现过（只有 Null/Static 桩），RAGFlow 时代同样拿不到这些字段。

## 1. 评审前已核实的事实（每条都有代码依据）

| 编号 | 事实 | 依据 |
| --- | --- | --- |
| F1 | `copies` / `stamped` 上传时从未采集 | `contracts/ingest.md` 表单字段表；`IngestDocument` 无此二字段；`MetadataKeys` 五键不含 |
| F2 | `period` / `scope` 在 WeKnora 通道刻意不下发引擎 | `HttpWeKnoraClient` 类注释：tag 槽专属 project，混用会污染权限判定 |
| F3 | `std_type` 全链路无来源 | `contracts/retrieval.md` 未定义该字段；上传表单无此项 |
| F4 | `GET /ingest/documents` 与 `/{docId}` 当前无任何鉴权 | `IngestController` 无安全注解；ingest 模块无 `SecurityFilterChain` / `HandlerInterceptor` / 过滤器 |
| F5 | `Checklist` record 不含 operator 字段 | `Checklist(String checklistId, String title, List<ChecklistItem> items)` |
| F6 | 上传人 `X-User-Id` 只写进审计事件，未随清单持久化 | `MatcherService.save()` → `store.addAudit(..., operator, ...)`；`AuditEvent` 持有 operator |
| F7 | 类型词典现有 **20 类** | `services-java/matcher/src/main/resources/data/types.yaml`（64 行，20 个 `std_name`） |
| F8 | 富化所需端点真实存在 | `IngestController:51` `GET /ingest/documents/{docId}` |
| F9 | HTTP 召回 provider 已落地并全绿 | commit `5d76a00`，matcher 54 项测试通过 |
| F10 | 内部身份 ID 形如 `u_0/u_1`，与企微 userid 不同源 | `InMemoryUserRepository.allocatePrincipalId()` 返回 `"u_" + seq`；`PrincipalResolver` 用 `user.principalId()` 作 `Principal.user_id` |
| F11 | `run` 是异步端点：立即返回 task_id、后台执行 | `MatcherController` `POST /checklists/{id}/run` → `executor.execute(() -> service.runTask(...))` |

> F7 更正说明：提案初稿写「80~120 类型」有误，已修正为 20 类。
> F5/F6 更正说明：提案初稿写「上传人随清单持久化」（误当成已有能力），已修正为本提案新增项；后因主体机制改由会话侧注入，该项已不再需要（见 §3 Q2 裁定）。
> F8/F10 更正说明：提案初稿让 matcher 用 `X-User-Id` 调 `/identity/principal/{wecomUserId}` 解析主体——经裁定该路径**不成立**（两类标识不同源，F10 亦印证），已从提案移除。

## 2. 三处契约变更（按评审关注点排序）

### C1 `contracts/ingest.md` —— 新增 3 个可选采集字段（只增不改）

`copies`（正整数）、`stamped`（true/false）、`std_type`（词典 code）。缺省即 null，后续硬约束判不通过，**不猜**。仅入 shilian 登记，**不推引擎**，`MetadataKeys` 不变。状态机与既有字段不动。

### C2 `contracts/matching.md` —— 新增「候选属性来源与匹配主体」一节（只增）

- 五个硬约束属性以 **ingest 登记为唯一权威来源**；matcher 按 `doc_id` 回查 `GET /ingest/documents/{doc_id}` 富化；查不到 → 该候选属性全缺、判不通过（宁缺勿错）。
- retrieval hits 的同名可选字段**仅作展示**，matcher 不得作为判定依据。
- 匹配主体的四元组由**服务端按登录会话注入**（Q6 已裁定：run 不直接暴露给浏览器）：BFF/网关从 session 取会话用户 ID → 向 wecom-sync 换四元组 → 注入 `POST /checklists/{id}/run` 请求体；matcher 原样透传给 retrieval-proxy，**自身不做任何 id→principal 解析、不调身份服务**。`X-User-Id` 仅作审计 actor。
- ⚠ 实现时别混淆：**session 给身份、不给权限**。四元组只在 wecom-sync 授权表里，那次「换取」查询不可省；但它是查权限、不是 id 转换（`Principal.user_id` 与 session 的会话用户 ID 同源）。
- principal 缺失或 dept 为空 → 任务 FAILED，`{"detail":"缺少会话权限主体"}`，**不回退匿名、不按默认密级**。
- 异步执行期间 principal 随任务在内存传递，**不得写入 `MatchTask` 序列化字段**（`GET /tasks/{id}` 直接返回它，写进去会把权限四元组回显给调用方）。
- `kb_scope` 来自配置 `MATCHER_KB_SCOPE`；未配置视为无权，召回返回空。

### C3 `contracts/retrieval.md` —— 语义澄清（**这是改语义，风险最高，请重点看**）

给 2026-10 补充的 hits 可选字段追加限定：属 best-effort 展示用途，需硬约束判定能力的消费方必须以 ingest 登记为准。

## 3. 已裁定项与待拍板问题

### 已裁定（2026-11-05）

| # | 结论 |
| --- | --- |
| ~~Q2~~ | **标识口径已裁定**：前端会话用户 ID 与企微 userid 是两个独立入口的用户标识，**互不相同、不做转换**。因此 matcher **不得**用 `X-User-Id` 调 `/identity/principal/{wecomUserId}`（会解析到错误的人）。提案初稿的错误路径已移除。 |
| ~~Q6~~ | **run 端点暴露面已裁定**（2026-11-05）：不直接暴露给浏览器，主体身份取自登录用户 session。链路定为：**BFF/网关**从 session 取会话用户 ID → 换取四元组 → 注入 run 请求体；matcher 只信服务端注入的 principal，不接受浏览器直连。 |

> 关键区分（避免把两件事混成一件）：**session 给的是「身份」，不是「权限」**。
> `dept / roles / max_sensitivity / projects` 只存在于 wecom-sync 授权表（`Grant`），
> session 里没有。所以 BFF 仍须用该 id 向 wecom-sync 换一次四元组——这一步是**查权限**，
> 不是 id 转换，不能省、也不能由 matcher 自行兜默认密级。
> 代码依据：`PrincipalResolver.resolve()` 返回的 `Principal.user_id` 即 `user.principalId()`
> （形如 `u_xxx`），与 session 里的会话用户 ID 同源，故无需任何 id 映射。

### 已决策（2026-11-09）

| # | 决策 |
| --- | --- |
| ~~Q1~~ | **std_type 来源**：方案甲先行（上传时人工传）+ 方案乙兜底（文件名对词典自动归一，20 类）。T02 词典扩充未完成，先按现有 20 类做自动归一，命不中保持 null。 |
| ~~Q3~~ | **ingest 鉴权**：补契约，措辞对齐 `matching.md` 红线 2（"生产必须置于网关鉴权后"）。挂 T16 治理项排期。 |
| ~~Q4~~ | **登记内存态**：挂 T16 治理项排期（生产须换 DB，测试环境暂用内存态）。 |
| ~~Q5~~ | **C3 改语义**：接受（假设无既有消费方依赖 hits 那四个字段做判定）。若后续发现消费方，需回滚或兼容。 |

## 4. 签字后实现切分（含验收口径）

| 步 | 模块 | 内容 | 验收 |
| --- | --- | --- | --- |
| 1 | ingest-java | 采集 + 登记 + 返回三字段；不推引擎 | 新增用例：缺省 null／非法值 400 |
| 2 | matcher | run 请求体接收 principal 并随任务内存传递（不写入 `MatchTask` 序列化字段）；`IngestRegistryClient`（按 doc_id 富化、带缓存）；runTask 把 principal 传入 `recall(item, principal)`；provider 接线装配；`X-User-Id` 仅作审计 actor | 富化失败→missing；principal 缺失／dept 空→任务 FAILED |
| 3 | retrieval-proxy | 仅文档澄清，**代码不动** | — |
| 4 | 部署 | matcher/packager 纳入 `deploy/test` compose + nginx 规则 | 53 冒烟：清单→匹配→manifest→packager 产物 |
| 5 | 回归 | 假阳性率验证依赖 T03 黄金评测集；未就绪先用脱敏样例清单验链路 | 链路通／指标待 T03 |

## 5. 评审时建议的核对顺序

1. 先看 **C3 + Q5**——唯一改语义的变更，牵动已上线消费方。
2. 然后 **Q1**（std_type 来源）——决定 T02 是否成为 WK18 的前置依赖。
3. Q3/Q4 属既有欠账，可当场定排期归属，不阻塞签字。

> Q2、Q6 已于 2026-11-05 裁定（标识不做转换；run 主体取自 session 经 BFF 注入），
> 不再是待拍板项；结论已并入 C2 与提案 §3.2。

## 6. 签字栏

| 角色 | 关注点 | 结论 | 签名 | 日期 |
| --- | --- | --- | --- | --- |
| 数智化中心 | BFF/网关侧 session→四元组注入由谁实现（Q6 已裁定方向）、Q3 鉴权、Q4 排期归属 | ☐ 通过　☐ 修改意见 | | |
| 后端A（ingest-java / retrieval-proxy） | C1 字段、C2 主体注入、C3 语义澄清、Q5 消费方 | ☐ 通过　☐ 修改意见 | | |
| 后端C（web 上传表单加三字段） | C1 对前端表单的影响 | ☐ 通过　☐ 修改意见 | | |

修改意见记录区（按角色追加，勿覆盖）：

- 数智化中心：
- 后端A：
- 后端C：

---

## 附：已提交但未推送的本地提交

| commit | 内容 |
| --- | --- |
| `5d76a00` | HTTP 召回 provider（matcher→retrieval-proxy），权限随请求下推，错误不外泄文档名 |
| `44fdce6` | 提案文档 + WK18 备注更新（含本文件前身的两处事实更正） |

> 现场状态：53 测试机 2026-11-04 起全端口不可达（8090/22 超时），部署与联调待网络恢复。
