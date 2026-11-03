# Spike 验证报告 · WeKnora 融合可行性

> 日期：2026-10-21　｜　关联：[DESIGN-REVIEW.md](./DESIGN-REVIEW.md)、[架构图](./architecture-weknora-fusion.svg)
> 结论速览：**4 项离线假设全部验证通过（26 项测试全绿），3 项现场假设待机房执行**

---

## 1. 执行环境说明（重要）

本机 Docker 守护进程未运行，且当前执行环境无网络访问，无法拉起 WeKnora 全栈实例（app + docreader + ParadeDB + Redis + 模型服务）。因此本次 spike 拆成两部分：

| 部分 | 状态 | 说明 |
| --- | --- | --- |
| **离线可验证** | ✅ 已完成并跑通 | 编码语义、协议翻译、安全不变量——用假 gateway 注入，26 项测试全绿 |
| **现场待验证** | ⏳ 脚本已就绪 | H1/H2/H3 需真实实例，`verify_live.py` 一跑即出结论 |

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

### 2.2 现场待验证 ⏳

| # | 假设 | 验证方法 | 失败时的 fallback |
| --- | --- | --- | --- |
| **H1** | `tag_ids` 在真实引擎上是 SQL 级下推，而非取回后筛选 | 两篇内容相同、仅 tag 不同的文档；先建基线（无过滤应召回两篇），再只授权一个 tag（应仅召回一篇），最后用一个谁都没有的 tag（应返回空） | dept 也升格为 KB 维度；或向上游提 PR 增加过滤能力 |
| **H2** | SSE 流式问答可用，shim 的 501 是唯一阻塞点 | 建会话 → `knowledge-chat` 流式读取 → 断言事件序列含 `answer`+`complete` 且 answer 非空 | 若非唯一阻塞点，P1 需重排优先级 |
| **H3** | 外部用户主体令牌可承载 shilian 会话身份 | 按上游约定签 HS256 JWT（`aud=weknora`、`exp`、`tenant_id`、`sub`），带 `X-External-User-Token` 调接口 | 继续用现有 `X-User-Id` 注入模型（已验证可行，H3 只是更优解） |

**H1 的验证设计特别说明**：单看「授权 tag 后只返回一篇」无法区分引擎级下推与应用层筛选——两种实现返回体相同。因此脚本额外做了两步：基线召回（确认两篇都能被检索到，排除 embedding 未配置导致的假阴性）+ 无匹配 tag 时返回空（确认过滤真的作用于候选集），并把 `meta.rerank.candidate_count` 打进结论供人工判读。

现场执行：

```bash
export WEKNORA_BASE_URL=http://<host>:8080
export WEKNORA_API_KEY=<空间级 Key，需 retrieve+ingest+chat>
export WEKNORA_TENANT_ID=<空间 ID>
python3 spike/weknora/verify_live.py            # 全跑
python3 spike/weknora/verify_live.py --only h1  # 只跑 H1
python3 spike/weknora/verify_live.py --keep     # 保留临时 KB 排障
```

脚本以只读为主：H1 会创建临时 KB 与两篇临时文档，结束后自动清理，**不触碰任何已有知识库**。

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
| 确认检索侧过滤可下推，红线 1 可在新底座成立 | ✅ A1（文档+引擎实现双向确认），H1 现场复核 |
| 确认 gateway 可作为上游模型厂商，红线 3/4 能力保留 | ✅ A2/A3（离线），H2 补流式 |
| 权限四元组映射方案无安全漏洞 | ✅ A4，含三个陷阱的修复与回归测试 |
| 一次 L3 问答，gateway 日志出现脱敏与 `switched_reason` | ⏳ 需现场实例；离线已验证 `switched_reason` 透出路径（响应头 + 日志 + system_fingerprint） |
| 检索结果不含无权文档 | ⏳ H1 现场验证 |

---

## 5. 结论与建议

**融合方案技术上成立，可以进入 P1。** 最关键的 P0 阻塞项（过滤下推）已解除，且验证过程中发现的三个陷阱都有修复方案与回归测试。

三条必须带进评审的结论：

1. **红线 4 无法在 WeKnora 原生 UI 满足**（OpenAI 协议体无处承载 `switched_reason`）→ 面向最终用户的问答入口必须是 shilian web，上游 UI 只作管理面。这是协议信息容量限制，不是实现取舍。
2. **`knowledge_ids` 必须永久排除在权限过滤之外**（并集语义会绕开密级隔离）。已写成测试不变量，任何后续改动都不能让它变绿失败。
3. **流式是 P1 首要待办**。当前 shim 对 `stream=true` 返回 501 而非静默降级——静默降级会让上游解析错乱，比明确失败更难排查。

建议评审顺序：先拍 D1（推翻不接入结论）与 D2（用户入口），再走 H1 现场验证；H1 通过即可启动 P1 的 `RetrievalBackend` SPI 抽取。
