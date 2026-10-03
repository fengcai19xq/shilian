"""契约数据模型（见 contracts/matching.md 与 contracts/packaging.md）。"""

from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field

UNKNOWN_TYPE = "UNKNOWN"


class Scope(str, Enum):
    """报表口径。"""

    CONSOLIDATED = "consolidated"  # 合并
    STANDALONE = "standalone"  # 单体 / 母公司


class ItemStatus(str, Enum):
    """清单项三态。"""

    MATCHED = "matched"
    PENDING = "pending"
    MISSING = "missing"


class Constraints(BaseModel):
    """清单项的硬约束，全部由代码判定，不交给模型。"""

    period: list[str] = Field(default_factory=list)  # 年份，如 ["2021","2022","2023"]
    scope: Scope | None = None
    copies: int | None = None
    stamp_required: bool = False


class ChecklistItem(BaseModel):
    item_id: str
    checklist_id: str
    no: str
    raw_text: str
    std_type: str = UNKNOWN_TYPE
    # 词典未命中时为 unknown：不猜类型，交人工补词典
    type_status: Literal["resolved", "unknown"] = "unknown"
    std_name: str | None = None
    constraints: Constraints = Field(default_factory=Constraints)


class Checklist(BaseModel):
    checklist_id: str
    title: str
    items: list[ChecklistItem] = Field(default_factory=list)


class CandidateDoc(BaseModel):
    """召回候选文档。属性由文档中台给出，缺失即为 None（按不通过处理）。"""

    doc_id: str
    doc_name: str
    uri: str = ""
    std_type: str = UNKNOWN_TYPE
    period: list[str] = Field(default_factory=list)
    scope: Scope | None = None
    copies: int | None = None
    stamped: bool | None = None
    pages: list[int] | None = None
    recall_score: float = 0.0
    form_score: float = 0.0


class ScoredCandidate(BaseModel):
    """打分后的候选。rejected 的候选 final_score 恒为 0，不参与排序择优。"""

    doc_id: str
    doc_name: str
    uri: str = ""
    rejected: bool = False
    reject_reasons: list[str] = Field(default_factory=list)
    recall_score: float = 0.0
    p_satisfies: float = 0.0
    form_score: float = 0.0
    final_score: float = 0.0
    decide_reason: str = ""


class ItemResult(BaseModel):
    item_id: str
    no: str
    std_type: str
    std_name: str | None = None
    status: ItemStatus
    score: float = 0.0
    candidates: list[ScoredCandidate] = Field(default_factory=list)
    bound_doc_ids: list[str] = Field(default_factory=list)
    confirmed_by: str | None = None
    note: str = ""


class MatchTask(BaseModel):
    task_id: str
    checklist_id: str
    state: Literal["queued", "running", "succeeded", "failed"] = "queued"
    total: int = 0
    done: int = 0
    error: str | None = None
    results: list[ItemResult] = Field(default_factory=list)

    @property
    def progress(self) -> float:
        return 1.0 if self.total == 0 else round(self.done / self.total, 4)


class AuditEvent(BaseModel):
    """状态变更审计事件：who / what / when。"""

    event_id: str
    ts: str
    checklist_id: str
    item_id: str | None = None
    action: str
    operator: str = "system"
    detail: dict = Field(default_factory=dict)


class ManifestFile(BaseModel):
    uri: str
    pages: list[int] | None = None
    order: int = 1


class ManifestEntry(BaseModel):
    no: str
    std_name: str
    files: list[ManifestFile] = Field(default_factory=list)
    status: ItemStatus
    note: str = ""


class Manifest(BaseModel):
    package_id: str
    title: str
    watermark: str = ""
    outputs: list[str] = Field(default_factory=lambda: ["zip", "merged_pdf", "checklist_xlsx"])
    entries: list[ManifestEntry] = Field(default_factory=list)
