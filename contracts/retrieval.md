# retrieval 契约

## 权限主体

```json
{
  "user_id": "u_10231",
  "dept": ["融资部", "财务共享"],
  "roles": ["financing_staff"],
  "max_sensitivity": "L3",
  "projects": ["PRJ-2026-CITIC"]
}
```

`max_sensitivity` ∈ `L1 | L2 | L3 | L4`，来自企微组织架构同步 + 人工授权表，**不由前端传入**，由网关根据会话态注入。

## 检索请求 `POST /retrieval/search`

```json
{
  "query": "最近三年研发费用占营收比例",
  "principal": { "...": "上面的权限主体" },
  "kb_scope": ["kb_finance", "kb_audit"],
  "top_k": 8,
  "score_threshold": 0.35,
  "mode": "probe | full"
}
```

`mode=probe` 用于路由判定：`top_k` 压到 3、不做 rerank、只返回最高分，成本约为 full 的 1/5。

## 返回

```json
{
  "hits": [
    {
      "chunk_id": "c_88213",
      "doc_id": "d_2031",
      "doc_name": "2023年度审计报告.pdf",
      "dept": "财务共享",
      "sensitivity": "L3",
      "page": 37,
      "score": 0.86,
      "text": "……研发费用 ……"
    }
  ],
  "scope_desc": "融资部 / 财务共享（你的权限内）",
  "total": 3
}
```

## 硬约束

1. 权限条件必须在调用 RAGFlow 之前翻译为 `metadata_condition`，**禁止先检索后过滤**
2. 越权文档不得出现在任何字段中，包括 `doc_name` 与错误信息
3. 用户无权访问时返回 `total: 0`，不返回"存在但无权"这类可推断存在性的信号
4. 每次检索写审计事件：who / query / scope / hit_doc_ids / ts
