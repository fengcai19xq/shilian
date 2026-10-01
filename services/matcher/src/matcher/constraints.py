"""硬约束校验：期间 / 口径 / 份数 / 盖章。

任一不通过直接 rejected，不参与打分（contracts/matching.md）。
判定全部是代码逻辑，属性缺失按不通过处理 —— 宁可漏一项，也不能错一项。
"""

from __future__ import annotations

from .models import CandidateDoc, ChecklistItem

PERIOD_MISMATCH = "period_mismatch"
PERIOD_UNKNOWN = "period_unknown"
SCOPE_MISMATCH = "scope_mismatch"
SCOPE_UNKNOWN = "scope_unknown"
COPIES_INSUFFICIENT = "copies_insufficient"
COPIES_UNKNOWN = "copies_unknown"
STAMP_MISSING = "stamp_missing"
STAMP_UNKNOWN = "stamp_unknown"
TYPE_MISMATCH = "type_mismatch"


def check_hard_constraints(item: ChecklistItem, candidate: CandidateDoc) -> list[str]:
    """返回否决原因列表，空列表表示全部通过。"""
    reasons: list[str] = []
    c = item.constraints

    if item.type_status == "resolved" and candidate.std_type != item.std_type:
        reasons.append(TYPE_MISMATCH)

    if c.period:
        if not candidate.period:
            reasons.append(PERIOD_UNKNOWN)
        elif not set(candidate.period) & set(c.period):
            # 2022 的报表匹 2023 的清单项：直接否决
            reasons.append(PERIOD_MISMATCH)

    if c.scope is not None:
        if candidate.scope is None:
            reasons.append(SCOPE_UNKNOWN)
        elif candidate.scope != c.scope:
            reasons.append(SCOPE_MISMATCH)

    if c.copies is not None:
        if candidate.copies is None:
            reasons.append(COPIES_UNKNOWN)
        elif candidate.copies < c.copies:
            reasons.append(COPIES_INSUFFICIENT)

    if c.stamp_required:
        if candidate.stamped is None:
            reasons.append(STAMP_UNKNOWN)
        elif not candidate.stamped:
            reasons.append(STAMP_MISSING)

    return reasons
