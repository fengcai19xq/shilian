# matching 契约

## 清单项

```json
{
  "item_id": "it_0031",
  "checklist_id": "cl_0007",
  "no": "3.1",
  "raw_text": "最近三年审计报告（合并口径，加盖公章）",
  "std_type": "AUDIT_REPORT",
  "constraints": {
    "period": ["2021", "2022", "2023"],
    "scope": "consolidated",
    "copies": 3,
    "stamp_required": true
  }
}
```

## 候选与打分

```text
final_score = 0.35 × recall_score
            + 0.50 × p(satisfies_item)
            + 0.15 × form_score

任一硬约束（期间 / 口径 / 盖章 / 份数）不通过 → rejected，不参与打分
```

## 三态

| 状态 | 阈值 | 界面表现 |
| --- | --- | --- |
| `matched` | `final_score ≥ 0.85` | 绿色 ✓，可直接入册 |
| `pending` | `0.5 ≤ score < 0.85` | 黄色 ?，必须人工确认 |
| `missing` | `< 0.5` 或无候选 | 红色 ✕，走缺件派单 |

阈值可配，但**调参方向以降低假阳性为准**：漏一项人会发现，错一项（把单体报表当合并报表发给银行）无人发现。

## 接口

```text
POST /matcher/checklists          上传并解析清单
POST /matcher/checklists/{id}/run 触发匹配（异步，返回 task_id）
GET  /matcher/tasks/{task_id}     进度与结果
POST /matcher/items/{id}/confirm  人工确认绑定 { doc_ids: [], note }
GET  /matcher/checklists/{id}/manifest  导出给 packager 的 manifest
```

## 硬约束

- 日期比较、份数统计、金额计算一律由代码完成，**不交给模型**
- 匹配只产生建议，`matched` 之外的状态不得自动入册
- 每次状态变更写审计事件，人工确认记录操作人
