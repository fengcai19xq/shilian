# WeKnora 融合方案 · 设计评审文档

> 状态：**待评审**　｜　日期：2026-10-21　｜　作者：数智化中心（AI 辅助起草）
> 关联架构图：[architecture-weknora-fusion.svg](./architecture-weknora-fusion.svg)
> 关联验证报告：[SPIKE-REPORT.md](./SPIKE-REPORT.md)
> 本文档为评审稿，**未改动 `contracts/`、`tasks.json`、`docs/progress.html` 及任何现有模块代码**。

---

## 0. 评审需要拍板的三个决策

| # | 决策项 | 建议 | 影响面 |
| --- | --- | --- | --- |
| D1 | 是否推翻 `tasks.json` 中「2026-10-01 不接入 WeKnora」的结论 | **推翻**，改为「接入 WeKnora 作为可插拔检索与解析底座」 | 该结论的前提是垂直系统定位；现已改为多租户通用底座，前提不成立 |
| D2 | 面向最终用户的问答入口用哪个 UI | **必须用 shilian web，WeKnora 原生 UI 只作管理面** | 见 §5.2 红线 4 的协议层限制，这是硬约束不是偏好 |
| D3 | RAGFlow 是否立即下线 | **不下线**，降级为可选适配器，保留作对照跑分 | 现有 retrieval-proxy / ingest 已按 RAGFlow 实现完毕，直接切会丢掉回归基准 |

---

## 1. 背景与定位变化

仓库现状是垂直系统：README 与 `tasks.json` 均指向「多部门文档入库、按权限问答溯源、清单自动成册」，并明确记录不接入 WeKnora。

新定位是**多租户、可扩展的通用知识库平台底座**。这带来两个变化：

1. 通用底座必须具备多租户隔离、多入口渠道、可插拔检索引擎、分级部署形态——这些自研成本极高；
2. 但通用底座市场是红海，shilian 的差异化必须落在**合规敏感**上：密级路由、检索前权限过滤、存在性不可推断、全链路审计、假阳性优先的高风险交付闭环。

结论：WeKnora 提供第 1 类能力，shilian 独占第 2 类能力。二者不是替代关系。

---

## 2. 融合原则

1. **不改 WeKnora 的 Go 代码**。用协议对接、把它当黑盒后端；确需改动的走「上游 PR + 本地 patch 队列」，禁止静默改 core。
2. **红线不下放**。密级、脱敏、过滤下推、出账、审计全部留在 shilian 侧，WeKnora 只承担通用能力。
3. **可插拔优先**。WeKnora 是 `RetrievalBackend` SPI 的一个适配器，不是唯一后端。
4. **先验证再动工**。任何依赖上游行为的设计，必须有 spike 结论支撑（见 SPIKE-REPORT.md）。

---

## 3. 分层职责

| 层 | 归属 | 说明 |
| --- | --- | --- |
| 文档解析（25+ 格式） | WeKnora docreader（Python + gRPC） | 直连 `:50051`，支持 mTLS 与 `GRPC_AUTH_TOKEN`；切块与元数据由 shilian ingest 掌控 |
| 检索流水线 | WeKnora app | 召回 → rerank → RRF 融合 → 截断，`POST /api/v1/knowledge-search` |
| 多租户空间 / RBAC / API Key | WeKnora | capability + `knowledge_base_ids` 白名单，构成平台层第二道边界 |
| Agent 编排 / IM 渠道 / MCP / Wiki / 记忆 | WeKnora | 取代 Dify；企微渠道上游已内置 |
| 密级路由 / 脱敏 / 出账 / 降级 | **shilian gateway** | 以「自定义 OpenAI 兼容厂商」身份被 WeKnora 调用 |
| 权限过滤下推 / 存在性保护 | **shilian retrieval-proxy** | WeKnora 是其一个后端适配器 |
| 密级标注 / held_l4 / 审计 | **shilian ingest（Java）** | 状态机与审计不动 |
| 清单匹配 / 成册 | **shilian matcher + packager** | 保持独立服务，不改造为 WeKnora Skill |
| 业务工作台 | **shilian web** | 管理面用 WeKnora 原生 UI |

---

## 4. 核心设计：权限如何落到 WeKnora 的三个过滤槽

### 4.1 上游能力事实

WeKnora `POST /api/v1/knowledge-search` 只有三个过滤字段，字段间 AND、字段内 IN(OR)：

```
knowledge_base_ids : []string   -> KB id IN (...)
knowledge_ids      : []string   -> 文件 id IN (...)
tag_ids            : []string   -> 标签 id IN (...)
```

PostgreSQL 引擎（ParadeDB，默认）实现为 `knowledge_base_id` / `knowledge_id` / `tag_id` 的 SQL WHERE 过滤，AND 语义；SQLite 引擎明确在 top-k 选择**之前**完成过滤。**过滤发生在检索引擎内部、不是取回后应用层筛选**——这满足红线 1。

### 4.2 两个语义陷阱（本方案的主要设计价值）

**陷阱 1：`knowledge_ids` 不能承载 project。**
官方文档明确「检索范围是 `knowledge_base_id(s)` 与 `knowledge_ids` 的并集」。用它装授权文件列表，会把这些文件所在 KB 的其余文件一并放行，等于绕开密级隔离。
→ **约束：`knowledge_ids` 永久保留不用**（除非业务确需"仅在指定文件里搜"，且此时不得同时传 KB）。已有测试 `test_knowledge_ids_always_empty` 钉死。

**陷阱 2：三个槽装不下三个 AND 维度，且 tag 槽不能混用。**
dept / sensitivity / project 三个维度需要 AND，但 tag 槽内部是 IN(OR)。若 dept 与 project 共用 tag 槽，「融资部 或 PRJ-2026-CITIC」会放过「风控部但同项目」的文档。
→ **解法：让 KB 同时编码 sensitivity 与 dept**，一次 IN 表达两个 AND 维度；tag 槽专属 project。

**附带发现：project 为空的语义差异。**
现有 `_is_allowed`（`services/retrieval-proxy/src/retrieval_proxy/service.py:56`）对 `chunk.project is None` 返回 True，即未标注项目的文档所有人可见。WeKnora 的 tag IN 过滤没有「NULL 也放行」语义，未打标签的文档会被静默过滤掉——功能上看不出来，但业务会漏料。
→ **解法：哨兵标签 `project::__none__`**，用户持有项目授权时一并纳入 `tag_ids`。

### 4.3 编码方案

| 维度 | 承载方式 | 示例 |
| --- | --- | --- |
| tenant | WeKnora 空间（`X-Tenant-ID`）+ KB 名前缀 | 空间 `acme` |
| sensitivity + dept | **复合编码进 KB 名** | `sl-acme-L3-878d8d89` |
| project | tag（`project::` 前缀） | `project::prj-2026-citic` |
| 未标注 project | 哨兵 tag | `project::__none__` |
| roles | 不参与检索过滤 | 仅供 decide 层 |

- KB 数量 = 密级(4) × 部门(N)。单企业 N≈5~10 → 20~40 个，可接受。
- 中文部门名按 unicode 码点 hex 化，保证可逆无碰撞；已有测试覆盖。
- 实现见 `spike/weknora/kb_encoding.py`，纯函数、零外部依赖、可逆性有测试。

### 4.4 可见范围推导

```
无 dept 授权          -> has_any_access=False，不发请求，返回 total=0
有 dept，无 project   -> kb_ids = dept × expand(密级)，不传 tag_ids（不限项目）
有 dept，有 project   -> 同上 + tag_ids = 授权项目 + project::__none__
```

`expand_sensitivity` 保持累加语义（L3 → [L1,L2,L3]），与 `contracts/retrieval.md` 一致。

---

## 5. 模型治理：让 gateway 成为 WeKnora 的厂商

### 5.1 集成方式

WeKnora 模型配置支持 `provider=generic`（自定义 OpenAI 兼容接口）+ `base_url` + `api_key`，且 `base_url` 经 SSRF 校验。把 `base_url` 指向 shim，WeKnora 的 LLM/embedding 调用全部流经 gateway，四条能力自动保留：L3 调用前脱敏、L4 只落 `vpc_self_hosted`、切换必带 `switched_reason`、按 dept+purpose+model 出账。**无需改一行 Go。**

### 5.2 密级识别：一把密钥绑定一个 (tenant, dept, 密级)

WeKnora 的模型配置是 **KB 级**的（每个知识库各自选模型），而 KB 已按密级切分 → 「密级」这个 WeKnora 没有的概念被编码进它的模型配置维度。这是整个方案最省力的对齐点。

密级由密钥决定、不由请求体决定，WeKnora 无法伪造密级（测试 `test_body_cannot_override_sensitivity` 覆盖）。

### 5.3 ⚠ 已知限制：红线 4 无法在 WeKnora 原生 UI 满足

红线 4 要求「模型自动切换通道时必须返回 `switched_reason`，前端显式提示」。OpenAI `chat.completions` 响应体**没有承载该字段的位置**，WeKnora 作为标准客户端会忽略未知字段。

| 入口 | 红线 4 是否满足 |
| --- | --- |
| shilian web 工作台 | ✅ shim 写审计 + 响应头，web 侧显式提示 |
| WeKnora 原生 UI | ❌ 切换原因无法在对话界面展示 |

**这是协议信息容量限制，不是实现偷懒。** 由此得出决策 D2：面向最终用户的问答入口必须是 shilian web。shim 仍将 `switched_reason` / `budget_alert` 写入响应头（中文 URL 编码）与审计，供 web 与排障使用；通道信息另借 `system_fingerprint` 字段透出（OpenAI 协议既有字段，WeKnora 不会因此报错）。

### 5.4 其他协议差异

- **流式**：WeKnora 对话默认走 SSE 流式，shim 当前返回 501 而非静默降级（避免 WeKnora 解析错乱）。流式支持列为 P1 待办。
- **上游错误**：只透出状态码，不透出响应体，防止夹带内容片段（红线 2 延伸）。
- **embedding**：映射到 `purpose=embed`，同样经密级路由——向量入库也属于 L3/L4 数据出域面。

---

## 6. 存量改造清单

| 模块 | 动作 | 返工量 |
| --- | --- | --- |
| retrieval-proxy | RAGFlow `metadata_condition` 方言 → 抽 `RetrievalBackend` SPI，新增 `WeKnoraAdapter`；RAGFlow adapter 保留 | 中 |
| ingest | 推送目标由 RAGFlow 改为 docreader / WeKnora；`held_l4` 状态机、审计、元数据登记**全部不动**；`chunk_id`/`page` 口径映射到 WeKnora 切块 | 中 |
| gateway | 新增 OpenAI 兼容入口（chat/completions、embeddings，后续补 rerank）；出账维度加 `tenant_id`；顺带偿还 `/gateway/invoke` → `/v1/invoke` 契约债 | 中 |
| Dify | **退役** | 大（本次最大改造项） |
| wecom-sync（T05） | 降级为单向同步器：企微组织架构 → WeKnora 空间与成员；IM 收发交上游 | 小 |
| web | 拆双前端：管理面用 WeKnora UI，业务工作台保留 shilian web | 中 |
| matcher / packager | **不动**。packager 保持独立服务与沙箱纪律，不改造为 Skill——成册是合规交付物，责任边界必须清晰 | 无 |
| 部署 | ParadeDB 与 shilian 业务库**分 database 或分 schema，禁止交叉**（上游启动时 `AUTO_MIGRATE` 只管自己的表）；Redis 分实例或分 DB index，避免 Asynq 与业务缓存互相挤占 | 小 |

---

## 7. 白拿的能力（不再自研）

| 能力 | 对应 shilian 原缺口 |
| --- | --- |
| Lite 单二进制（sqlite-vec，无 Redis 时 Asynq 退化为进程内执行器） | 部署太重、无长尾形态 |
| Helm Chart + systemd 裸机 | 分级交付 |
| docreader 25+ 格式 | 现仅 docx/pdf/xlsx/pptx 四种 |
| 数据源连接器（飞书/Notion/语雀/RSS） | 企业知识库刚需 |
| Wiki 模式 | 生成 `period/scope` 候选标注、人工终审，缓解标注瓶颈 |
| MCP Server（`/mcp/:endpoint_id`，每端点独立 Bearer） | 知识库对外发布 |
| 长记忆（跨会话） | 平台留客 |
| Langfuse profile | LLM 调用级可观测，与 gateway 出账互为第二视角 |
| `dataset/` 评测集 | 对接 T03 黄金评测集 |
| 外部用户主体（`X-External-User-Token` HS256 JWT，带 `tenant_id` claim） | 与现有 `X-User-Id` 注入模型几乎一一对应 |

---

## 8. 安全与升级纪律

WeKnora 2026 年被披露过 SQL 注入至 RCE、MCP 命令注入、跨租户越权、KB 克隆越权、SSRF。融合后这些面就是 shilian 的面：

1. 锁版本 + 跟踪 advisory，升级走灰度；
2. 生产 `DISABLE_REGISTRATION=true` 或 `invite_register`，身份走 OIDC 对接企业 IdP；
3. 开 `SSRF_DNS_WHITELIST_ONLY`；MCP endpoint 默认关闭、按需开且独立 token；
4. API Key capability 最小化（检索类不给 `ingest` / `manage_kbs`）；
5. `8080` 不暴露公网，仅内网可达；
6. **L4 不出 VPC**：自建 WeKnora + 本地模型 + ParadeDB 全链路自托管天然满足，这是相对公有云 RAG 的结构性优势；
7. 上线前针对**多租户场景**做红队测试——平台跨租户泄露一次，密级卖点就地崩塌；
8. 默认零改动上游；确需改动走「上游 PR + 本地 patch 队列」。

---

## 9. 分期路线

| 期 | 内容 | 验收口径 |
| --- | --- | --- |
| **Spike** | 验证 tag 下推真实性、gateway 作为厂商被调通、流式、外部主体令牌 | ✅ **已全部完成**：离线 4 项（26 项测试）+ 现场 H1/H2/H3 三项 PASS（2026-11-04，见 SPIKE-REPORT.md §2.2 与 §4） |
| **P1** | gateway OpenAI 兼容层 + tenant 出账；`RetrievalBackend` SPI 与 WeKnoraAdapter；双后端并存 | 同一评测集在 RAGFlow / WeKnora 跑分对比 |
| **P2** | ingest 切 docreader；Dify 退役；web 双前端切分 | T01 端到端在新底座跑通 |
| **P3** | Wiki 辅助标注、MCP 发布、数据源连接器、Lite 形态、多租户红队 | 红队报告零高危 |

---

## 10. 风险与缓解

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| `tag_ids` 在真实引擎上的下推行为与文档不符 | ~~高~~ **已排除** | H1 现场验证通过（2026-11-04）：无匹配 tag 返回 0 篇，确为引擎级下推，无需上游 PR。**附带结论：未打标签文档会被 tag 过滤排除，`project::__none__` 哨兵标签为必需** |
| KB 数量随部门增长膨胀（4×N，多租户再乘 tenant） | 中 | 先评估真实部门数上限；必要时 dept 改为 tag + KB 只编码密级，代价是多一道应用层校验 |
| 流式未实现导致 WeKnora 原生问答不可用 | 中 | H2 现场验证通过：底座 SSE 链路完整（`references`/`answer`/`complete` 齐全），阻塞点只剩我们 gateway 的 `stream=true → 501`；D2 已规定用户入口用 shilian web |
| 上游迭代快，patch 队列失控 | 中 | 坚持零改动；H1 已通过，**当前无引入 patch 的理由** |
| **开启 `signed_token` 会让纯 API Key 调用一起 401**（H3 新发现） | 高 | 该开关是**空间级全局**、不按调用可切，与「gateway 作为 OpenAI 兼容厂商接入」的形态直接冲突。对接前必须先盘清该空间内**全部** API Key 调用方并逐一改为带令牌，否则表现为底座整体不可用。现网保持 `mode=tenant` |
| **误以为 WeKnora 能替我们做用户级授权**（H3 新发现） | 高 | 实测不同 `sub` 的合法令牌看到的知识库**完全相同**：上游只验签、不鉴权。红线 1（权限过滤必须在检索请求里完成）**仍全部由 shilian 承担**，H3 只解决「上游知道这是谁」以便审计与配额 |
| 上游 CVE 传导 | 高 | §8 纪律 + 红队 |
| 人力与额度不足（现有 3 个任务因额度 blocked） | 高 | 白拿能力优先做减法（退役 Dify、复用 docreader），而非加功能 |

---

## 11. 不做的事

- 不把 matcher / packager 改造成 WeKnora Skill；
- 不用 WeKnora 空间模型直接替代密级模型（语义不同，硬套会丢掉「假阳性优先」的业务纪律）；
- 不在 spike 结论出来前重写 `permissions.py`；
- 不把 `knowledge_ids` 用于权限过滤；
- 不让业务标签参与 `tag_ids` 权限过滤（IN 的 OR 语义会污染权限判定）。

---

## 12. 评审结论记录区

| 决策 | 结论 | 评审人 | 日期 |
| --- | --- | --- | --- |
| D1 推翻不接入结论 | **通过**——接入 WeKnora 作为可插拔检索与解析底座 | 数智化中心 | 2026-11-03 |
| D2 用户入口用 shilian web | **通过**——WeKnora 原生 UI 只作管理面（红线 4 协议层限制） | 数智化中心 | 2026-11-03 |
| D3 RAGFlow 降级保留 | **通过**——降级为可选 adapter，保留作对照跑分 | 数智化中心 | 2026-11-03 |
| §4.3 KB 复合编码方案 | **通过**——spike 26 项测试验证，三个陷阱均有修复与回归 | 数智化中心 | 2026-11-03 |
| §8 安全纪律 | **通过**——锁版本+OIDC+SSRF白名单+红队 | 数智化中心 | 2026-11-03 |

> 评审依据：
> - spike/SPIKE-REPORT.md 离线 4 项假设全部验证通过（26 项测试全绿）
> - **现场 H1/H2/H3 三项假设全部 PASS（2026-11-04 直连 53 实例验完）**，结论与两条新增高风险约束见 §10 与 SPIKE-REPORT.md §2.2
> - 53 测试环境 WeKnora 全栈已部署并跑通（app+docreader+postgres+redis+frontend 五容器 healthy）
> - 千问 API（qwen-plus）和 TEI embedding（bge-m3）已配通，文档解析→检索→问答全链路验证通过
> - nginx SSE/API 分离修复已落地，前端登录超时问题已解决
>
> 契约层变更已落地：`contracts/platform.md`（WK04）、`contracts/weknora-integration.md`（WK05）。
> 启动 W1 阶段。
>
> ⚠ 现场验证新增一条**待拍板**（尚无决策签字）：`signed_token` 为空间级全局开关，开启后纯 API Key 调用一并失效，与 D1「gateway 作为 OpenAI 兼容厂商接入」的形态冲突。
> 建议：现网维持 `mode=tenant`；是否切 `signed_token` 留到 WK17 OIDC 统一登录落地后再议（若身份已由 shilian 侧校验，则不依赖上游该开关）。**该建议需数智化中心确认后再执行。**
