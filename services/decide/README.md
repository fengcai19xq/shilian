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
| `DECIDE_DECIDER_TIMEOUT` | `2` | jev/llm 单次判定超时秒数，超时按降级处理 |
| `DECIDE_DECIDER_FALLBACK` | `rule` | 判定失败/超时/返回无效时的降级目标；`none` 不降级直接抛错（仅调试） |
| `DECIDE_DECIDER_SENSITIVITY` | `L2` | 调 gateway 判定时声明的问题密级 |
| `DECIDE_AB_TRAFFIC_SPLIT` | 空 | 灰度配比 `ab.traffic_split`，如 `rule:80,jev:10,llm:10`；按 user_id 哈希稳定分桶，为空则全量走 `DECIDE_BACKEND` |
| `DECIDE_AB_SHADOW` | 空 | 影子后端 `ab.shadow`，如 `jev`；非空时主路径固定用 rule 返回，同时调用影子后端只记录对比 |
| `DECIDE_AB_SHADOW_ASYNC` | `true` | 影子调用放后台线程，不增加响应延迟 |
| `DECIDE_AB_SALT` | `decide-ab-v1` | 分桶哈希盐，换盐即整体重新洗桶 |

## Jev 接入与灰度（T14）

- `JevDecider` 经 gateway `POST /v1/invoke`（`purpose=decide`、`preferred_model=jev-decide`、principal 只带 `user_id + dept` 用于记账），
  解析 `payload.need_internal_probability`（0~1，兼容只给 `label`）与可选 `reason`；超时、gateway 异常、返回无效
  一律按配置降级到 rule，原因写入 `evidence.decider_fallback_reason`（如「jev 调用超时（超过 2s 未返回），已降级到纯规则」）。
- `evidence.decider_backend` 为实际生效的实现（降级后为 `rule`）。`ab_bucket` 不在契约字段内，只写日志：
  `decide.routing` 记录 `decide_verdict {...}`（含 ab_bucket、成本、延迟、jev reason），`decide.ab` 记录影子对比
  `decide_shadow {...}`；日志只记 user_id / 问题的哈希，不落原文。

## A/B 离线评测

```bash
python -m decide.eval --dataset tests/data/eval_set.jsonl --backends rule,jev,llm            # 表格
python -m decide.eval --dataset tests/data/eval_set.jsonl --format json --output report.json
python -m decide.eval --dataset ... --gateway http --gateway-url http://localhost:8002     # 真实 gateway
```

输出每个后端的 accuracy / precision / recall / f1（以「需要内部资料」为正类）、按类别准确率、整条路由准确率、
平均成本（取 gateway `usage.cost_cny`，缺失时按 token × 单价估算）、平均/P95 延迟与降级次数。
`tests/data/eval_set.jsonl` 为 33 条脱敏合成样本（企业问题 12 / 通用问题 11 / 库内无命中 10），T03 黄金集到位后替换。
默认 `--gateway fake` 使用离线 `SimulatedGateway`（关键词模拟、不联网），**数值只用于验证评测链路，不代表 Jev 真实效果**。
