# Spike 验证报告 · WeKnora 融合可行性

> 日期：2026-10-21　｜　关联：[DESIGN-REVIEW.md](./DESIGN-REVIEW.md)、[架构图](./architecture-weknora-fusion.svg)
> 结论速览：**4 项离线假设全部验证通过（26 项测试全绿），3 项现场假设待机房执行**

---

## 1. 执行环境说明（重要）

本机 Docker 守护进程未运行，无法在本地拉起 WeKnora 全栈实例（app + docreader + ParadeDB + Redis + 模型服务）。因此本次 spike 拆成两部分：离线部分在本机用假 gateway 注入验证设计语义；现场部分直连 53 上已部署的实例（`http://192.168.200.53:8090`）完成。

| 部分 | 状态 | 说明 |
| --- | --- | --- |
| **离线可验证** | ✅ 已完成并跑通 | 编码语义、协议翻译、安全不变量——用假 gateway 注入，26 项测试全绿 |
| **现场验证** | ✅ 三项全部通过（2026-11-04） | 53 上的真实实例直连完成 H1/H2/H3，详见 §2.2；过程中修掉验证脚本自身的四个缺陷 |

这个拆分不是妥协：**离线部分恰好覆盖了「设计是否会出错」，现场部分只覆盖「上游行为是否与文档一致」**。前者是设计风险（已由测试钉死），后者是集成风险（有明确 fallback）。

---

## 2. 假设清单与结论

### 2.1 离线已验证 ✅

#### A1 — `knowledge-search` 支持元数据级过滤，可满足红线 1

**这是原方案里唯一的 P0 阻塞项**（上一轮明确标注「⚠️ 我没有在官方文档中确认」）。现已通过官方 API 文档与引擎实现文档双向确认：

- `POST /api/v1/knowledge-search` 请求体含 `tag_ids []string`（标签过滤）、`knowledge_base_ids`、`knowledge_ids`；
- PostgreSQL 引擎（ParadeDB，默认）实现为 `knowledge_base_id` / `knowledge_id` / `tag_id` 的 **IN 过滤，AND 语义**，`is_enabled` 为 NULL 或 true；
- SQLite 引擎更进一步，源码注释明确：向量路径用 `v.rowid IN (SELECT ... FROM lite_embeddings filtered WHERE ...)`，**在 top-k 选择前完成过滤**，并解释「若先取全局 top-k 再过滤，范围内的有效匹配可能无法召回」。

**判定：过滤发生在检索引擎内部，不是取回后应用层筛选 → 红线 1 在 WeKnora 侧成立。** 这是整个融合方案可行的前提，现已确认。

#### A2 — gateway 可作为 WeKnora 的模型厂商，无需改 Go

WeKnora 模型配置支持 `provider=generic`（自定义 OpenAI 兼容接口）+ `base_url` + `api_key`，`base_url` 经 SSRF 校验。已实现 shim 并完成协议双向翻译：

| 方向 | 验证点 |
| --- | --- |
| WeKnora → gateway | OpenAI `chat.completions` / `embeddings` 请求体正确翻译为 `InvokeRequest`（purpose / sensitivity / preferred_model / principal / payload） |
| gateway → WeKnora | `InvokeResponse` 还原为合法 OpenAI 响应形状（`object` / `choices` / `usage` / `finish_reason`） |

**判定：成立。** 密级路由、L3 脱敏、L4 通道限制、按 dept+purpose+model 出账四条能力全部保留。

#### A3 — 密级可由 API Key 承载，且请求体无法伪造

WeKnora 的模型配置是 **KB 级**的（每个知识库各自选模型），而 KB 已按密级切分 → 密级被编码进上游的模型配置维度。shim 侧「一把密钥绑定一个 (tenant, dept, 密级)」：

- `test_l3_key_routes_to_l3_and_l1_key_to_l1`：不同密钥路由到不同密级；
- `test_body_cannot_override_sensitivity`：请求体里塞 `sensitivity: L1` 与伪造 `principal` **一律被忽略**。

**判定：成立，且这是整个方案最省力的对齐点。**

#### A4 — 权限四元组可无损映射到 WeKnora 的过滤槽

映射方案（详见 DESIGN-REVIEW.md §4.3）：tenant→空间，sensitivity+dept→**复合编码进 KB 名**，project→tag，roles→不参与检索。

验证过程中发现并解决了 **三个真实陷阱**，这是本次 spike 最有价值的产出：

**陷阱 1 — `knowledge_ids` 与 KB 取并集，不能承载 project。**
官方文档明确「检索范围是 `knowledge_base_id(s)` 与 `knowledge_ids` 的并集」。用它装授权文件列表，会把这些文件所在 KB 的其余文件一并放行 → **绕开密级隔离**。
→ 约束：`knowledge_ids` 永久保留不用。测试 `test_knowledge_ids_always_empty` 把它钉成安全不变量。

**陷阱 2 — 三个槽装不下三个 AND 维度，tag 槽不能混用。**
tag 槽内部是 IN(OR)。若 dept 与 project 共用，「融资部 或 PRJ-2026-CITIC」会放过「风控部但同项目」的文档。
→ 解法：KB 同时编码 sensitivity 与 dept，一次 IN 表达两个 AND 维度；tag 槽专属 project。

**陷阱 3 — project 为空的语义在两个后端不一致。**
现有 `_is_allowed`（`services/retrieval-proxy/src/retrieval_proxy/service.py:56`）对 `chunk.project is None` 返回 True（未标注项目的文档所有人可见）。WeKnora 的 tag IN 过滤**没有「NULL 也放行」语义**，未打标签的文档会被静默过滤掉。
→ 这类缺陷最危险：功能测试全绿、权限看起来更严了，但业务侧会**无声漏料**。
→ 解法：哨兵标签 `project::__none__`，持有项目授权时一并纳入 `tag_ids`。测试 `test_project_tags_include_none_sentinel` 覆盖。

另验证编码可逆性（`test_kb_name_is_reversible_for_every_dept_and_sensitivity`）与中文部门 slug 无碰撞（`test_slug_is_collision_free_across_depts`）——**编码不可逆会导致 ingest 打标与检索解析对不上**，属于必须提前排除的隐患。

### 2.2 现场验证 ✅ 三项全部通过（2026-11-04，53 实例）

| # | 假设 | 结论 | 实测证据 |
| --- | --- | --- | --- |
| **H1** | `tag_ids` 是引擎级下推，而非取回后筛选 | **通过** | 基线（无 tag）召回 2 篇；只授权 auth → 仅召回 auth 一篇；只授权 forb → 仅召回 forb；用一个谁都没有的 tag → **0 篇**。应用层筛选做不到第三步。 |
| **H2** | SSE 流式问答可用 | **通过** | 事件序列 `agent_query → tool_call → tool_result → references → answer → complete`，answer 396 字符，references 2 条。 |
| **H3** | 外部主体令牌可承载 shilian 会话身份 | **通过（有重要前提，见下）** | 合法令牌 200；伪造签名 / 过期 / 跨租户 / `alg=none` / 垃圾串 **全部 401**。 |

**H2 附带收获**：answer 正文里带内联引用标记 `<kb doc="credit.txt" chunk_id="..." kb_id="...">`，`chunk_id` 可直接用于 shilian 侧的溯源与页码映射，不必额外回查。

**H1 顺带确认哨兵标签为必需**：未打标签的文档在基线可见、但一旦传 `tag_ids` 就被排除——与 §2.1 陷阱 3 的推断一致，`project::__none__` 哨兵不能省。

#### H3 的两个必须先解决的问题（对接前必须拍板）

1. **`signed_token` 是空间级全局开关，不是按调用可切**。一旦开启，**所有不带令牌的 API Key 调用会一起 401**（实测：`仅 API Key` → 401）。这与「gateway 作为 OpenAI 兼容厂商接入 WeKnora」的形态冲突——除非每次调用都由 shilian 现签一枚令牌。开启前必须确认调用方全覆盖，否则表现为「底座整体挂掉」。
2. **WeKnora 只验签、不做用户级授权**。实测不同 `sub` 的令牌看到的知识库集合**完全相同**。所以红线 1（权限过滤在检索请求里完成）**仍然且必须由 shilian 承担**，H3 打通只是让 WeKnora 侧能识别「这是谁」以便做审计与配额，**不能**指望它替我们隔离数据。

次要观察：`iat` 不校验（未来时间戳被接受）；`require_direct_header=true` 时缺令牌会被拒（可用于强制模式）。

现场执行：

```bash
export WEKNORA_BASE_URL=http://<host>:8080
export WEKNORA_API_KEY=<空间级 Key>
export WEKNORA_TENANT_ID=<空间 ID>
python3 spike/weknora/verify_live.py --only h1  # 逐项跑，见下方注意事项
python3 spike/weknora/verify_live.py --only h2
WEKNORA_EXTERNAL_USER_SECRET=<HMAC 密钥> python3 spike/weknora/verify_live.py --only h3
```

**注意：H3 要单独跑。** `signed_token` 模式一开，H1/H2 的纯 API Key 调用会全部 401（见上文问题 1），因此三项不能一把全跑；跑 H3 前先开、跑完立刻复原 `mode=tenant`。

脚本以只读为主：H1 创建临时 KB 与两篇临时文档，结束自动清理，**不触碰任何已有知识库**；H3 只读写空间的 principal 配置，脚本内不做该变更（避免给现场留残留），由执行人显式开关。

#### 本轮现场同时修掉了验证脚本自身的四个缺陷

这几个坑都是「脚本错、代码没错」，记录下来避免再次误判底座能力：

1. **上传时 `tag_ids` 必须是裸 id 字符串**。传 JSON 数组字符串（`'["<id>"]'`）会被当成一个不存在的标签名，服务端直接 `HTTP 400 标签 [...] 不存在`。生产代码 `services-java/ingest/HttpWeKnoraClient.multipart` 用的是裸 id 重复字段形态，**本来就是对的**——只有 spike 脚本写错了。
2. **建库时必须绑定向量模型**（`embedding_model_id`）。否则文档解析必然失败并报 `failed to get embedding model: model ID cannot be empty`，表现为「180 秒超时、无法判定」，容易被误读成 docreader 故障。也不要改用 `/initialization/initialize`：该接口字段校验严格，且会把已有配置覆盖成空。
3. **H2 原先盲选 `rows[0]`**，撞上空库或解析失败的库就会判成失败——改为挑确实含已解析文档的库。
4. **H3 原判据只看「合法令牌返回 200」就判 PASS**，这是假阳性：`mode=tenant` 下该 header 被整个忽略，**伪造签名同样返回 200**。现改为必须同时满足「合法 200」与「五种伪造全部 401」才通过。这一条最值得记住——**验证安全能力时，反向判据不是加分项，是必需项**。

---

## 3. 已产出的可运行资产

```
spike/
├── DESIGN-REVIEW.md                     融合方案评审文档（含 12 节 + 决策记录区）
├── SPIKE-REPORT.md                      本报告
├── architecture-weknora-fusion.svg      融合架构图（1680×1180，已通过 XML 合法性校验）
├── pyproject.toml                       spike 独立依赖与 pytest 配置
└── weknora/
    ├── kb_encoding.py                   权限四元组 → WeKnora 过滤槽的编码（纯函数，零外部依赖）
    ├── gateway_shim.py                  OpenAI 兼容 shim（FastAPI）
    ├── verify_live.py                   H1/H2/H3 现场验证脚本
    └── tests/test_spike.py              26 项测试
```

复跑：

```bash
cd spike && python3 -m pytest weknora/tests/test_spike.py -q
```

**范围声明**：本次未改动 `contracts/`、`tasks.json`、`docs/progress.html` 及 `services/`、`services-java/` 下任何现有文件。spike 代码独立于生产模块，`kb_encoding.py` 刻意不复用 `permissions.py` 的实现，避免在验证阶段触碰生产代码——两者语义一致性由测试断言保证。

---

## 4. 验收口径（对照 DESIGN-REVIEW.md §9 的 Spike 期）

| 验收项 | 状态 |
| --- | --- |
| 确认检索侧过滤可下推，红线 1 可在新底座成立 | ✅ A1（文档+引擎实现双向确认）+ **H1 现场复核通过** |
| 确认 gateway 可作为上游模型厂商，红线 3/4 能力保留 | ✅ A2/A3（离线）+ **H2 现场流式通过**（`stream` 事件链完整、含 references） |
| 权限四元组映射方案无安全漏洞 | ✅ A4，含三个陷阱的修复与回归测试；H1 实测确认哨兵标签为必需 |
| 一次 L3 问答，gateway 日志出现脱敏与 `switched_reason` | ⏳ 需端到端环境（WK13）；离线已验证 `switched_reason` 透出路径（响应头 + 日志 + system_fingerprint），底座侧流式能力由 H2 确认 |
| 检索结果不含无权文档 | ✅ **H1 现场验证通过**：无匹配 tag 返回 0 篇，未授权文档不进入结果 |

---

## 5. 结论与建议

**融合方案技术上成立，P0 阻塞项已全部解除。** 离线 26 项测试钉住设计语义，现场 H1/H2/H3 三项假设全部通过（2026-11-04）；验证过程中发现的三个陷阱都有修复方案与回归测试。

四条必须带进决策的结论：

1. **红线 4 无法在 WeKnora 原生 UI 满足**（OpenAI 协议体无处承载 `switched_reason`）→ 面向最终用户的问答入口必须是 shilian web，上游 UI 只作管理面。这是协议信息容量限制，不是实现取舍。
2. **`knowledge_ids` 必须永久排除在权限过滤之外**（并集语义会绕开密级隔离）。已写成测试不变量，任何后续改动都不能让它变绿失败。
3. **流式的阻塞点确实只在 shilian 侧**——H2 证实底座 SSE 链路完整可用，而 `services/gateway/openai_compat.py:197` 对 `stream=true` 仍返回 501。这是**我们自己的待办**，不再是底座风险。保持明确失败、不静默降级的做法是对的：降级会让上游解析错乱，比 501 更难排查。
4. **H3 可用但不能当成权限方案**（详见 §2.2）：`signed_token` 是空间级全局开关，开启后所有纯 API Key 调用一起 401；且 WeKnora **只验签、不做用户级授权**（不同 `sub` 看到的库完全相同）。红线 1 的权限过滤仍且必须全部由 shilian 承担。

方法论上值得留一条：**验证安全能力时，反向判据不是加分项，是必需项**。H3 原判据只看「合法令牌拿到 200」，而该 header 在未开启的模式下被整个忽略——伪造签名同样能拿到 200。差一步就把「功能没生效」读成「功能已验证通过」。
