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
| F8 | 提案引用的两个端点均真实存在 | `IngestController:51` `GET /ingest/documents/{docId}`；`IdentityController:73` `GET /identity/principal/{wecomUserId}` |
| F9 | HTTP 召回 provider 已落地并全绿 | commit `5d76a00`，matcher 54 项测试通过 |

> F7 更正说明：提案初稿写「80~120 类型」有误，已修正为 20 类。
> F5/F6 更正说明：提案初稿写「上传人随清单持久化」（误当成已有能力），已修正为**本提案新增项**。

## 2. 三处契约变更（按评审关注点排序）

### C1 `contracts/ingest.md` —— 新增 3 个可选采集字段（只增不改）

`copies`（正整数）、`stamped`（true/false）、`std_type`（词典 code）。缺省即 null，后续硬约束判不通过，**不猜**。仅入 shilian 登记，**不推引擎**，`MetadataKeys` 不变。状态机与既有字段不动。

### C2 `contracts/matching.md` —— 新增「候选属性来源与匹配主体」一节（只增）

- 五个硬约束属性以 **ingest 登记为唯一权威来源**；matcher 按 `doc_id` 回查 `GET /ingest/documents/{doc_id}` 富化；查不到 → 该候选属性全缺、判不通过（宁缺勿错）。
- retrieval hits 的同名可选字段**仅作展示**，matcher 不得作为判定依据。
- 匹配主体 = 清单上传人；解析失败 → 任务 FAILED，`{"detail":"身份解析失败"}`，**不回退匿名、不按默认密级**。
- `kb_scope` 来自配置 `MATCHER_KB_SCOPE`；未配置视为无权，召回返回空。

### C3 `contracts/retrieval.md` —— 语义澄清（**这是改语义，风险最高，请重点看**）

给 2026-10 补充的 hits 可选字段追加限定：属 best-effort 展示用途，需硬约束判定能力的消费方必须以 ingest 登记为准。

## 3. 需评审拍板的 5 个开放问题

| # | 问题 | 起草方倾向 | 为何不能自决 |
| --- | --- | --- | --- |
| Q1 | **`std_type` 来源**：上传时人工传（方案甲）／文件名对词典自动归一（方案乙，20 类）／两者并存 | 甲先行，乙作兜底 | 涉及业务建档流程，且 T02 词典扩充未完成 |
| Q2 | **标识口径同源**：`X-User-Id`（前端语境为「会话用户 ID」，测试形如 `u_1`）与 `/identity/principal/{wecomUserId}`（企微 userid）是否同一命名空间？ | 待后端A + 数智化中心确认 | **安全项**：若不同源，matcher 会拿内部 id 当企微 id 解析，可能得到错误或空主体 → 越权风险。代码无法自证 |
| Q3 | **`GET /ingest/documents` 鉴权**：F4 已核实当前裸露可枚举。契约是否补「生产必须置于网关鉴权后」 | 补，措辞对齐 `matching.md` 红线 2 | 属既有暴露面，修法超出 WK18 范围，需数智化中心定排期 |
| Q4 | **登记内存态**：`InMemoryDocumentRepository` 重启丢数据 → 富化查不到会全落 missing。测试环境可接受，生产须换 DB | 挂到 T16 治理项排期 | 跨任务排期决策 |
| Q5 | **C3 改语义是否可接受**：有无已上线消费方依赖 hits 那四个字段做判定？ | 若无则接受 | 需后端A/后端C 确认消费方现状 |

## 4. 签字后实现切分（含验收口径）

| 步 | 模块 | 内容 | 验收 |
| --- | --- | --- | --- |
| 1 | ingest-java | 采集 + 登记 + 返回三字段；不推引擎 | 新增用例：缺省 null／非法值 400 |
| 2 | matcher | `Checklist` 增 operator 并持久化；`IngestRegistryClient`（按 doc_id 富化、带缓存）；runTask 解析 principal 传入 `recall(item, principal)`；provider 接线装配 | 富化失败→missing；principal 解析失败→任务 FAILED |
| 3 | retrieval-proxy | 仅文档澄清，**代码不动** | — |
| 4 | 部署 | matcher/packager 纳入 `deploy/test` compose + nginx 规则 | 53 冒烟：清单→匹配→manifest→packager 产物 |
| 5 | 回归 | 假阳性率验证依赖 T03 黄金评测集；未就绪先用脱敏样例清单验链路 | 链路通／指标待 T03 |

## 5. 评审时建议的核对顺序

1. 先看 **Q2**（安全，阻塞性最高）——它决定 matcher 能否安全解析主体，若不同源则 C2 需改写。
2. 再看 **C3 + Q5**——唯一改语义的变更，牵动已上线消费方。
3. 然后 **Q1**（std_type 来源）——决定 T02 是否成为 WK18 的前置依赖。
4. Q3/Q4 属既有欠账，可当场定排期归属，不阻塞签字。

## 6. 签字栏

| 角色 | 关注点 | 结论 | 签名 | 日期 |
| --- | --- | --- | --- | --- |
| 数智化中心 | Q2 标识口径、Q3 鉴权、Q4 排期归属 | ☐ 通过　☐ 修改意见 | | |
| 后端A（ingest-java / retrieval-proxy） | C1 字段、C3 语义澄清、Q5 消费方 | ☐ 通过　☐ 修改意见 | | |
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
