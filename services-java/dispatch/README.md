# dispatch —— 缺件企微派单与回传闭环（T12）

JDK 17 + Spring Boot 3.3 + Maven。输入为 matcher 产出的 `missing` / `pending` 清单项，按资料类型派单给责任人（企微卡片），
跟踪回传、验收 / 驳回，超时自动提醒并升级到部门负责人；每次状态变化写审计事件。

**回传不判定匹配**：回传只把工单置为 `uploaded` 并通知 matcher 重新匹配，是否 `matched` 由 matcher 侧确认。

## 运行与测试

```bash
cd services-java/dispatch
mvn -q test                 # JUnit 5，全部使用内存 fake
mvn spring-boot:run         # 默认端口 8012
```

## 配置（环境变量）

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DISPATCH_PORT` | `8012` | 服务端口 |
| `DISPATCH_ROUTING_FILE` | `classpath:dispatch-routing.yml` | 派单规则文件，支持 `file:/path/x.yml` |
| `DISPATCH_TIMEOUT_HOURS` | `48` | 指派（或驳回）后超过多少小时未回传即提醒 + 升级，可为小数 |
| `DISPATCH_UPLOAD_URL_BASE` | `http://localhost:5173/dispatch/tickets` | 卡片「去上传」链接前缀，最终为 `{base}/{ticket_id}` |
| `DISPATCH_SCAN_INTERVAL_MS` | `300000` | 超时扫描间隔 |

本模块不持有任何密钥。企微真实实现所需的 corp_id / agent_id / secret 由实现方从环境变量读取（见「未完成」）。

## 派单规则（`src/main/resources/dispatch-routing.yml`）

```yaml
departments:
  finance: {name: 财务部, head: u_fin_head, default_assignee: u_fin_01}
rules:
  - {std_type: AUDIT_REPORT, dept: finance, assignee: u_fin_audit}
  - {std_type: FINANCIAL_STATEMENT, dept: finance}
default: {dept: admin}
```

选人优先级：

1. `rules` 中命中 `std_type` 的规则（`assignee` 缺省 → 部门 `default_assignee` → 部门 `head`）
2. 清单项自带的 `owner_dept`（该部门 `default_assignee` → `head`）
3. `default` 规则

升级对象一律为所属部门的 `head`。加载时校验部门引用、`head` 必填、`std_type` 不重复，配置错误启动即失败。
工单的 `routed_by` 字段记录命中的是 `std_type` / `owner_dept` / `default` 哪一级。

## 工单状态机

```text
open ──派单──▶ assigned ──回传──▶ uploaded ──验收──▶ accepted（终态）
                  ▲                  │
                  │                  └──驳回──▶ rejected ──再次回传──▶ uploaded
              超时：提醒负责人 + 升级部门负责人（不改状态，重新计时）
```

- 建单后立即按规则派单（`open → assigned`）并发企微卡片；通知失败不阻塞派单，记 `notify_failed` 审计，后续超时扫描仍会催办。
- `assigned` / `rejected` 状态从 `assigned_at`（或驳回时刻）起计时，超过 `DISPATCH_TIMEOUT_HOURS` 未回传：
  给负责人发「催办」、给部门负责人发「升级」（两者同一人时只发一条），`escalation_count + 1`，并从当前时刻重新计时。
- 同一 `checklist_id + item_id` 已有未结单（非 `accepted`）工单时不重复建单，返回在 `skipped` 中。

## 接口

操作人取自网关注入的请求头 `X-User-Id`（缺省记为 `system`）。错误体统一为 `{"detail": "..."}`：
400 请求体不是合法 JSON；403 无权；404 工单不存在；409 状态机不允许；422 校验失败。时间均为 UTC ISO-8601。

### POST /dispatch/tickets —— 批量建单

`items[]` 字段沿用 `contracts/matching.md` 清单项命名；`period` 可直接给，也可放在 `constraints.period`；
`status` 只接受 `missing` / `pending`。任一项校验失败整批不落库（422）。

```http
POST /dispatch/tickets
X-User-Id: u_pm

{
  "checklist_id": "cl_0007",
  "items": [
    {"item_id": "it_0031", "no": "3.1", "raw_text": "最近三年审计报告（合并口径，加盖公章）",
     "std_type": "AUDIT_REPORT", "status": "missing",
     "constraints": {"period": ["2021", "2022", "2023"], "scope": "consolidated"}},
    {"item_id": "it_0072", "std_type": "OTHER", "status": "pending", "period": ["2024"], "owner_dept": "legal"}
  ]
}
```

`201 Created`：

```json
{
  "checklist_id": "cl_0007",
  "created": [
    {
      "ticket_id": "tk_000001", "checklist_id": "cl_0007", "item_id": "it_0031", "no": "3.1",
      "std_type": "AUDIT_REPORT", "std_name": null, "raw_text": "最近三年审计报告（合并口径，加盖公章）",
      "period": ["2021", "2022", "2023"], "item_status": "missing", "owner_dept": null,
      "dept": "finance", "dept_name": "财务部", "assignee": "u_fin_audit", "dept_head": "u_fin_head",
      "routed_by": "std_type", "status": "assigned", "doc_ids": [], "last_doc_id": null,
      "reject_reason": null, "reviewed_by": null,
      "created_at": "2026-10-08T00:00:00Z", "updated_at": "2026-10-08T00:00:00Z",
      "assigned_at": "2026-10-08T00:00:00Z", "due_at": "2026-10-10T00:00:00Z",
      "escalation_count": 0, "last_escalated_at": null
    }
  ],
  "skipped": []
}
```

（`created` 中第二张工单省略。）同一请求再提交一次时，两项都会进入 `skipped`：
`{"item_id": "it_0031", "ticket_id": "tk_000001", "reason": "active_ticket_exists"}`。

### GET /dispatch/tickets?checklist_id=cl_0007[&status=uploaded] —— 按清单查工单

```json
{"checklist_id": "cl_0007", "total": 1, "tickets": [{"ticket_id": "tk_000001", "status": "uploaded", "...": "..."}]}
```

缺 `checklist_id` 或 `status` 非法 → 422。

### POST /dispatch/tickets/{id}/callback —— 回传

```http
POST /dispatch/tickets/tk_000001/callback
X-User-Id: u_fin_audit

{"doc_id": "d_audit_2023", "note": "扫描件已盖章"}
```

校验顺序：工单存在（404）→ `doc_id` 非空（422）→ 当前状态可迁到 `uploaded`，即 `assigned` / `rejected`（409）
→ 操作人是工单负责人或部门负责人（403）→ 文档库中存在该 `doc_id`（422）→ 文档 `std_type` 与工单一致（文档无类型时跳过，422）。
通过后把 `doc_id` 回写到 `doc_ids` / `last_doc_id`，状态置为 `uploaded`，再调用 `RematchNotifier` 请 matcher 重新匹配
（失败只记 `rematch_failed` 审计，不回滚）。返回更新后的工单：

```json
{"ticket_id": "tk_000001", "status": "uploaded", "doc_ids": ["d_audit_2023"], "last_doc_id": "d_audit_2023", "due_at": null, "...": "..."}
```

### POST /dispatch/tickets/{id}/accept —— 验收

请求体可选 `{"note": "无误"}`。仅 `uploaded` 可验收（否则 409）。返回 `status=accepted`、`reviewed_by=<X-User-Id>`。

### POST /dispatch/tickets/{id}/reject —— 驳回

```json
{"reason": "缺少公章"}
```

`reason` 必填（422），仅 `uploaded` 可驳回（409）。状态置为 `rejected`、写 `reject_reason`、重新计时，并给负责人发驳回通知；
负责人可再次回传。

### GET /dispatch/tickets/{id} 、GET /dispatch/tickets/{id}/events —— 单个工单 / 审计事件

```json
[
  {"event_id": "ev_000001", "ts": "2026-10-08T00:00:00Z", "ticket_id": "tk_000001", "checklist_id": "cl_0007",
   "item_id": "it_0031", "action": "created", "from_status": null, "to_status": "open", "operator": "u_pm",
   "detail": {"item_status": "missing", "std_type": "AUDIT_REPORT", "period": ["2021", "2022", "2023"]}},
  {"event_id": "ev_000002", "action": "assigned", "from_status": "open", "to_status": "assigned",
   "detail": {"dept": "finance", "assignee": "u_fin_audit", "dept_head": "u_fin_head", "routed_by": "std_type",
              "due_at": "2026-10-10T00:00:00Z"}, "...": "..."}
]
```

`action` 取值：`created` / `assigned` / `uploaded` / `accepted` / `rejected`（状态变化），
`escalated` / `notify_failed` / `rematch_failed`（不改状态，`from_status == to_status`）。

### POST /dispatch/escalations/run —— 手动触发一次超时扫描

生产由定时任务执行；此接口供运维与联调。返回 `{"total": 1, "escalated": [<ticket>]}`。

## 外部系统（可注入接口 + 内存 fake）

| 接口 | 默认实现 | 用途 |
| --- | --- | --- |
| `WecomNotifier` | `InMemoryWecomNotifier` | 企微应用消息：`sendText` / `sendCard`（textcard） |
| `DocumentVerifier` | `InMemoryDocumentVerifier` | 回传校验：`doc_id` 是否已入库及其 `std_type` |
| `RematchNotifier` | `InMemoryRematchNotifier` | 回传后请 matcher 重新匹配 |
| `AuditSink` | `InMemoryAuditSink` | 审计事件落库 |
| `TicketRepository` | `InMemoryTicketRepository` | 工单存储 |

默认 Bean 都带 `@ConditionalOnMissingBean`，提供同类型 Bean 即可替换为真实实现。

## 未完成

- 真实企微实现（gettoken + message/send，凭据从环境变量读）、文档库校验与 matcher 重匹配的 HTTP 实现、审计与工单的持久化，均只有接口 + 内存 fake。
- 企微回调（用户在企微内直接上传文件）未接入；当前回传由前端 / 网关上传入库后调用 callback。
- `contracts/matching.md` 的清单项尚无 `owner_dept` 字段，本模块按可选字段接收；建议在契约中补充（只增不改）。
