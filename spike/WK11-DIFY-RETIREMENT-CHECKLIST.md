# WK11 Dify 退役检查清单

> 状态：代码侧验证已完成，现场退役操作待执行

## 1. 判定路径覆盖验证

原 Dify 工作流中的判定步骤已全部由 `services/decide` 模块接管，覆盖矩阵如下：

| Dify 工作流判定路径 | decide 模块实现 | 代码位置 | 覆盖状态 |
|---|---|---|---|
| 用户显式选企业知识库 | `UserChoice.ENTERPRISE` → `RouteDecision.ENTERPRISE` | `routing.py:46-51` | ✅ |
| 用户显式选通用模型 | `UserChoice.GENERAL` → `RouteDecision.GENERAL` | `routing.py:52-56` | ✅ |
| 企业实体命中 → 走企业知识 | `EntityMatcher.match()` → `RouteDecision.ENTERPRISE` | `routing.py:59-65` | ✅ |
| 探测检索命中 → 走企业知识 | `probe.top_score >= threshold` → `RouteDecision.ENTERPRISE` | `routing.py:84-95` | ✅ |
| 探测检索未命中，需判定是否需要内部资料 | `Decider.decide()` 判定 | `routing.py:98-123` | ✅ |
| 需要内部资料但未命中 → 如实告知 | `need_internal_probability >= threshold` → `RouteDecision.KB_EMPTY` | `routing.py:110-121` | ✅ |
| 不需要内部资料 → 通用模型 | `need_internal_probability < threshold` → `RouteDecision.GENERAL` | `routing.py:123` | ✅ |
| 检索服务不可用 → fail-closed | `probe.available == False` → `RouteDecision.KB_EMPTY` + `allow_general_fallback=False` | `routing.py:71-83` | ✅ |
| 判定模型（Jev/LLM）失败 → 降级规则 | `FallbackDecider` 包装 primary，失败走 `RuleDecider` | `deciders.py:246-271` | ✅ |

## 2. 红线保持验证

| 红线 | decide 模块保证 | 代码位置 |
|---|---|---|
| 红线 2: 库内未命中禁止静默转通用模型 | `KB_EMPTY` 状态 `confirm_required=True` + `allow_general_fallback=False` | `routing.py:78-83, 118-121` |
| 红线 4: 模型自动切换显式提示 | decide 走 gateway，gateway 返回 `switched_reason` | `deciders.py:180-192` |
| 省 token: 判定走小模型不走大模型 | `purpose=decide`，preferred_model=`jev-decide` 或 `qwen2.5-7b-instruct` | `deciders.py:229, 236` |
| 省 token: 路由探测用 probe 模式 | `mode=probe`，`top_k=3`，不 rerank | `routing.py:127-133` |

## 3. 编排职能迁移

| 原 Dify 职能 | 迁移到 | 验证状态 |
|---|---|---|
| 问答路由判定（企业/通用/库空） | `services/decide` `POST /decide/route` | ✅ 已落地，89 项测试通过 |
| 问答编排（检索 → 生成 → 引用） | WeKnora `agent-chat` / `chat_pipeline` | ✅ WK13 现场验通 |
| 知识库检索 | `services/retrieval-proxy` + WeKnora `knowledge-search` | ✅ 双后端可切换 |
| 模型调用 | `services/gateway` `POST /v1/invoke` + OpenAI 兼容层 | ✅ WK06 已落地 |
| 文档入库解析 | `services-java/ingest` + WeKnora docreader | ✅ WK10 已落地 |
| IM 收发 | WeKnora 内置企微渠道 | ✅ WK14 已确认 |

## 4. 退役操作步骤（现场执行）

1. **确认 decide 服务健康**：`curl http://decide:8000/healthz` 返回 `{"status":"ok"}`
2. **确认 BFF 已代理 decide**：`curl http://bff:8000/api/decide/route` 可路由
3. **停止 Dify 容器**：`docker stop dify-server dify-api dify-web`（或 compose down 对应服务）
4. **确认无 Dify 相关告警**：检查网关日志无 Dify 调用、BFF 日志无 Dify 转发
5. **清理 Dify 配置**：移除 compose 中 Dify 服务定义、移除 .env 中 Dify 相关变量
6. **端到端回归**：跑 `deploy/test/smoke.sh`，确认问答链路完整

## 5. 代码侧结论

**decide 模块已完整覆盖原 Dify 工作流中的全部判定路径，红线 2/4 全部保持，省 token 策略已落地。代码侧无阻塞项，退役为运维操作。**
