# ingest 契约（T06 文档入库）

服务：`services-java/ingest`，端口 `8030`。JSON 字段一律 snake_case。

## 上传 `POST /ingest/documents`

`multipart/form-data`，请求头 `X-User-Id` 必填（由网关注入，写入审计 actor）。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | docx / pdf / xlsx / pptx |
| `dept` | 是 | 归属部门名称，与 principal.dept 同口径 |
| `sensitivity` | 是 | `L1 \| L2 \| L3 \| L4` |
| `project` | 否 | 项目编号，与 principal.projects 同口径 |
| `period` | 否 | 期间，如 `2023`、`2023Q4` |
| `scope` | 否 | 单体 / 合并 |
| `title` | 否 | 缺省取文件名 |

- 缺 `dept` / `sensitivity`（或取值非法）、缺 `file`、文件为空、缺 `X-User-Id` → `400 {"error":"bad_request","message":"…"}`，不登记、不写审计
- 成功登记 → `202`，`Location: /ingest/documents/{doc_id}`，body 为文档对象（异步模式下 `status=uploaded`）

## 查询

- `GET /ingest/documents/{doc_id}` → 文档对象；不存在 `404 {"error":"not_found"}`
- `GET /ingest/documents?dept=&status=` → `{"documents":[文档对象…],"total":n}`；参数为空表示不过滤；未知 status → 400

## 文档对象

```json
{
  "doc_id": "d_000001",
  "title": "2023年度审计报告",
  "file_name": "2023年度审计报告.pdf",
  "file_type": "pdf",
  "size_bytes": 102400,
  "dept": "财务共享",
  "sensitivity": "L3",
  "project": "PRJ-2026-CITIC",
  "period": "2023",
  "scope": "合并",
  "status": "indexed",
  "reason": null,
  "page_count": 120,
  "chunk_count": 310,
  "dataset_id": "ds_1",
  "ragflow_doc_id": "rf_doc_2",
  "uploaded_by": "u_10231",
  "created_at": "2026-10-11T08:00:00Z",
  "updated_at": "2026-10-11T08:00:05Z"
}
```

## 状态机

```
uploaded ──► parsing ──► indexed
   │            └──────► failed   （reason：加密 / 损坏 / 不支持的类型 / 无文本需 OCR / 推送 RAGFlow 失败）
   └──► held_l4                   （L4：只登记，不解析、不推送，reason="L4 不出 VPC：…"）
```

`indexed` / `failed` / `held_l4` 为终态。

## 推送 RAGFlow 的元数据

文档级与每个切块都带同一组元数据，键名与 `retrieval.md` 一致：`dept`、`sensitivity`、`project`、`period`、`scope`；可选字段为空时不写该键。切块另带 `page`（PDF 物理页 / xlsx 工作表序号 / pptx 幻灯片序号 / docx 按分页符估算，均从 1 开始），`chunk_id = {doc_id}_c{4 位序号}`。

## 审计事件

每次状态变化一条：`actor`（X-User-Id）、`doc_id`、`action`（= 新状态：uploaded / parsing / indexed / failed / held_l4）、`from_status`、`sensitivity`、`reason`、`ts`。
