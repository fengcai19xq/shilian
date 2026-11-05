# WK18 契约变更草案：候选属性改由 shilian 登记富化（待签字）

状态：**提案，未生效**。签字前 `contracts/` 不动。
日期：2026-11-04　起草：千问工作助理（WK18）　评审人：数智化中心 + 后端A（retrieval-proxy / ingest-java）+ 后端C（web）

---

## 1. 问题：matcher 的五类硬约束在真实链路上无数据可用

`matching.md` 的硬约束（类型 / 期间 / 口径 / 份数 / 盖章）依赖候选文档携带
`std_type / period / scope / copies / stamped`。逐字段核对现状（全部有代码依据）：

| 字段 | 上传时采集？ | shilian 登记（IngestDocument） | 进引擎（RAGFlow meta_fields） | 进引擎（WeKnora） | retrieval hits 承诺？ |
| --- | --- | --- | --- | --- | --- |
| period | ✅ | ✅ | ✅（MetadataKeys 五键含它） | ❌ 刻意不下发 | ✅ |
| scope | ✅ | ✅ | ✅ | ❌ 同上 | ✅ |
| copies | ❌ **从没采集** | ❌ | ❌ | ❌ | ✅（空头承诺） |
| stamped | ❌ **从没采集** | ❌ | ❌ | ❌ | ✅（空头承诺） |
| std_type | ❌ 文档侧无来源 | ❌ | ❌ | ❌ | ❌ 契约里也没有 |

- WeKnora 通道不下发 period/scope 是**有意设计**（`HttpWeKnoraClient` 类注释：
  tag 槽专属 project，混用会污染权限判定），不改。
- `retrieval.md` 2026-10 补充承诺的四个可选字段，`copies / stamped` 从未有人生产过——
  不是 WeKnora 切换引入的回归，RAGFlow 时代同样拿不到。
- 后果：直接接线真实召回，任何清单项五类约束全判「属性缺失→不通过」，
  全部落 missing/rejected。成册闭环不可能在现约定下跑通。

## 2. 备选方案与否决理由

| 方案 | 否决理由 |
| --- | --- |
| A. 给 retrieval hits 增补全部属性、经引擎往返 | 引擎要保持可插拔；WeKnora（外部代码，零改动红线）不会替我们存 copies/stamped/std_type 这类业务字段；等于把业务 schema 焊进检索方言，双底座语义分叉 |
| B. 类型判定交给 decide | decide 判「是否满足该项」需要知道双方类型，输入仍是同样的字段；回避不了采集问题 |
| C.（本草案）matcher 按 doc_id 回查 shilian 登记富化 | shilian 是这些属性的权威方；引擎只留权限相关元数据（dept/密级/project 已够用）；`宁缺勿错`语义不变 |

## 3. 变更清单（三处，均为「只增」或语义澄清）

### 3.1 `contracts/ingest.md` —— 新增可选采集字段（只增不改）

上传表单新增三个**可选**字段；文档对象顶层同名字段返回：

```text
| copies   | 否 | 份数，正整数（成册核对表用） |
| stamped  | 否 | 是否盖章，true/false |
| std_type | 否 | 归一化资料类型（词典 code，如 AUDIT_REPORT）；见 §3.4 MVP 说明 |
```

- 缺省（不传）＝ null，后续硬约束判不通过——**不猜**。
- 仅入 shilian 登记，**不推引擎**（与 period/scope 在 WeKnora 通道同待遇），
  `MetadataKeys` 不变，避免污染权限过滤槽。
- 状态机、既有字段、审计事件不变。

### 3.2 `contracts/matching.md` —— 新增「候选属性来源与匹配主体」一节（只增）

```text
## 补充（提案，2026-11）

- 候选文档的硬约束属性（std_type / period / scope / copies / stamped）以
  ingest 登记为唯一权威来源：matcher 对召回命中的每个 doc_id 调
  GET /ingest/documents/{doc_id} 富化；登记查不到该 doc_id → 该候选属性全缺、
  判不通过（宁缺勿错）。retrieval hits 的同名可选字段仅作展示用途，
  matcher 不得作为判定依据。
- 匹配主体的四元组由**服务端按登录会话注入**（2026-11-05 裁定：run 端点不直接暴露给
  浏览器）：BFF/网关从登录 session 取会话用户 ID → 向 wecom-sync 换取四元组 →
  注入 `POST /checklists/{id}/run` 请求体；matcher 原样透传给 retrieval-proxy（红线 1），
  **自身不做任何 id→principal 解析**。
  注意区分：**session 提供的是身份，不是权限**——`dept/roles/max_sensitivity/projects`
  只在 wecom-sync 授权表（Grant）里，session 没有，故那次换取查询不可省略。
  代码依据：`PrincipalResolver.resolve()` 的 `Principal.user_id` 即 `user.principalId()`
  （形如 `u_xxx`），与 session 里的会话用户 ID 同源，**无需 id 转换**。
  请求头 X-User-Id 只作审计 actor（对齐 matching.md 既有条款），不参与权限判定。
- principal 缺失或 dept 为空 → 任务 FAILED，错误体 {"detail":"缺少会话权限主体"}；
  **不回退匿名、不按默认密级**（宁缺勿错，避免静默越权或静默丢料）。
- run 是异步的（立即返回 task_id、后台执行），principal 须在受理时捕获并随任务
  在内存中传递；**不得写入 MatchTask 的序列化字段**——GET /tasks/{id} 直接返回
  MatchTask，写进去会把权限四元组回显给调用方。
- matcher → retrieval-proxy 的 kb_scope 来自配置（MATCHER_KB_SCOPE，
  逗号分隔）；未配置视为无权，召回返回空（与 has_any_access 同语义）。
```

### 3.3 `contracts/retrieval.md` —— 语义澄清（**这是改语义，必须评审**）

2026-10 补充中 hits 可选字段一句，追加限定：

```text
- 上述可选字段为 best-effort 展示用途：后端未提供即 None；
  需要硬约束判定能力的消费方（matcher）必须以 ingest 登记为准（见 matching.md）。
```

### 3.4 std_type 的文档侧来源（开放问题，给两个 MVP 选项）

- **方案甲（推荐先做）**：上传时人工传 `std_type`（融资部建档时本来就知道这是什么资料）。
  词典 T02 未就绪也能跑通闭环，后续 T02 完成后加文件名启发 + 校验。
- **方案乙**：文件名对词典（`matcher` 的 `TypeDictionary` 已存在，`types.yaml` 现有
  **20 类**；T02 是扩充它）自动归一，命不中保持 null。可作为甲的兜底并存。

## 4. 实现切分（签字后按序执行）

| 步 | 模块 | 内容 | 验收 |
| --- | --- | --- | --- |
| 1 | ingest-java | 采集+登记+返回三字段；不推引擎 | 新增用例：缺省 null / 非法值 400 |
| 2 | matcher | run 请求体接收 principal 并随任务内存传递（不写入 MatchTask 序列化字段）；`IngestRegistryClient`（按 doc_id 富化，带缓存）；runTask 把 principal 传入 `recall(item, principal)`；HttpCandidateProvider 接线装配；X-User-Id 仅作审计 actor | 富化失败→missing；principal 缺失/dept 空→任务 FAILED |
| 3 | retrieval-proxy | §3.3 澄清仅文档，代码不动 | — |
| 4 | 部署 | matcher/packager 纳入 deploy/test compose + nginx 规则 | 53 冒烟：清单→匹配→manifest→packager 产物 |
| 5 | 回归 | 假阳性率验证依赖 T03 黄金评测集（未就绪则先用脱敏样例清单验链路） | 链路通 / 指标待 T03 |

## 5. 风险与对策

- **登记是内存态**（InMemoryDocumentRepository），重启丢数据 → 富化查不到全落 missing。
  测试环境可接受；生产前须换 DB 实现（建议挂到 T16 治理项一并排期，本草案不展开）。
- **越权面**：`GET /ingest/documents` 目前无鉴权可枚举——富化调用在容器内网走，
  但契约应注明该接口生产必须置于网关鉴权后（与 matching.md 红线2同措辞）。签字时一并确认。
- **谁注入 principal（2026-11-05 已裁定方向）**：run 端点不直接暴露给浏览器，主体身份取自
  登录 session——由 BFF/网关完成「session → 向 wecom-sync 换四元组 → 注入 run 请求体」。
  matcher 只信服务端注入的 principal，不接受浏览器直连传入的权限字段。
  待确认仅剩实现归属：该注入逻辑落在现有 web 网关还是新建 BFF（不影响本契约条文）。
- provider 已按「std_type 缺失＝UNKNOWN 判不通过」实现（commit 5d76a00），
  与 §3.4 方案甲衔接后无需改判定逻辑。

## 6. 签字栏

| 角色 | 结论 | 日期 |
| --- | --- | --- |
| 数智化中心 | ☐ 通过 ☐ 修改意见 | |
| 后端A（ingest/retrieval） | ☐ 通过 ☐ 修改意见 | |
| 后端C（web 上传表单加三字段） | ☐ 通过 ☐ 修改意见 | |
