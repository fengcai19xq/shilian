# decide —— decide() 路由与 Jev 接入

对应任务：T08 T14
接口契约：`contracts/gateway.md`（只读）

问答路由与结构化判定。库内未命中必须如实告知，禁止静默转通用模型。保留纯规则兜底。

## 开发约定

- 只读写本目录，跨模块需求走契约，不直接改其它模块代码
- 对外只暴露契约里定义的接口，内部结构自由
- 单元测试与本模块同目录，`pytest` 可独立跑通
- 不在代码里硬编码模型密钥与数据库口令，一律读环境变量

## 接口

`POST /decide/route`：输入用户问题 + principal + 用户显式选择（`enterprise | general | auto`），输出路由结果。

```json
// 请求
{ "question": "研发费用占营收比例", "principal": { "user_id": "u_10231", "dept": ["融资部"] },
  "user_choice": "auto", "kb_scope": ["kb_finance"] }
// 返回
{ "decision": "enterprise | general | kb_empty", "reason": "……",
  "confirm_required": false, "allow_general_fallback": false,
  "evidence": { "source": "user_choice | entity | probe | decide", "matched_entities": [],
                "probe_top_score": 0.86, "probe_total": 3, "probe_threshold": 0.55,
                "need_internal_probability": null, "decider_backend": null,
                "decider_fallback_reason": null, "scope_desc": "融资部（你的权限内）" } }
```

路由顺序：显式选择 → 企业实体命中 → `mode=probe`（top_k=3）探测最高分 ≥ 阈值 → `decide()` 判定。
判定「需要内部资料」但未检索到时返回 `kb_empty` + `confirm_required=true`，**绝不自动转通用模型**；
`general` 返回体会剥掉命中实体与检索范围等公司信息。

## 如何本地跑

```bash
cd services/decide
python3 -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'

python -m pytest          # 单元测试（全部使用 fake，不访问外部服务）
ruff check . && ruff format --check .

# 启动服务（retrieval / gateway 地址按需改）
DECIDE_RETRIEVAL_BASE_URL=http://localhost:8001 \
DECIDE_GATEWAY_BASE_URL=http://localhost:8002 \
uvicorn decide.app:app --port 8003
```

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DECIDE_BACKEND` | `rule` | 判定实现：`rule`（纯规则兜底）/ `jev` / `llm`；后两者经 gateway 调用，失败自动降级到 `rule` |
| `DECIDE_PROBE_SCORE_THRESHOLD` | `0.55` | 探测最高分 ≥ 该值即走企业知识 |
| `DECIDE_NEED_INTERNAL_THRESHOLD` | `0.5` | 判定概率 ≥ 该值视为需要内部资料 |
| `DECIDE_PROBE_TOP_K` | `3` | 探测检索 top_k |
| `DECIDE_ENTITY_VOCAB_FILE` | 空 | 企业实体词表文件，一行一个词，`#` 为注释 |
| `DECIDE_ENTITY_VOCAB` | 空 | 额外实体词，逗号分隔 |
| `DECIDE_RETRIEVAL_BASE_URL` / `DECIDE_GATEWAY_BASE_URL` | `localhost:8001/8002` | 依赖服务地址 |
| `DECIDE_HTTP_TIMEOUT` | `5` | HTTP 超时秒数 |
