# 问答路由契约（decide）

`POST /decide/route`

请求：`{question, principal, user_choice: enterprise|general|auto (默认 auto), kb_scope}`，`principal` 同 retrieval.md。

响应：
```json
{
  "decision": "enterprise | general | kb_empty",
  "reason": "string",
  "confirm_required": false,
  "allow_general_fallback": true,
  "evidence": {
    "source": "user_choice | entity | probe | decide",
    "matched_entities": [], "probe_top_score": 0.0, "probe_total": 0, "probe_threshold": 0.55,
    "need_internal_probability": 0.0, "decider_backend": "rule | jev | llm",
    "decider_fallback_reason": null, "scope_desc": null
  }
}
```

硬约束：`kb_empty` 时 `confirm_required=true、allow_general_fallback=false`，前端须二次确认后才可发起 general 请求；`general` 的 evidence 不含 `matched_entities / scope_desc`，请求第三方模型时不得携带 principal 与任何公司数据。
