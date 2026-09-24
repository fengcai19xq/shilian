"""把权限四元组翻译为 RAGFlow 的 metadata_condition。

红线：过滤条件必须随检索请求一起下发给 RAGFlow，禁止先取结果再过滤。
"""

from __future__ import annotations

from typing import Any

from .models import SENSITIVITY_ORDER, Principal


def expand_sensitivity(max_sensitivity: str) -> list[str]:
    """把密级上限展开为可见密级集合，例如 L3 -> [L1, L2, L3]。"""
    if max_sensitivity not in SENSITIVITY_ORDER:
        raise ValueError(f"未知密级：{max_sensitivity}")
    upper = SENSITIVITY_ORDER.index(max_sensitivity)
    return list(SENSITIVITY_ORDER[: upper + 1])


def build_metadata_condition(principal: Principal) -> dict[str, Any]:
    """构造 RAGFlow metadata_condition（多个条件之间取 and）。

    - dept in 用户所属部门
    - sensitivity in 不超过 max_sensitivity 的密级集合
    - project in 用户授权项目（projects 为空时不下发该条件，表示不限定项目）
    """
    conditions: list[dict[str, Any]] = [
        {"name": "dept", "comparison_operator": "in", "value": list(principal.dept)},
        {
            "name": "sensitivity",
            "comparison_operator": "in",
            "value": expand_sensitivity(principal.max_sensitivity),
        },
    ]
    if principal.projects:
        conditions.append(
            {"name": "project", "comparison_operator": "in", "value": list(principal.projects)}
        )
    return {"op": "and", "conditions": conditions}


def has_any_access(principal: Principal, kb_scope: list[str]) -> bool:
    """无部门授权或无可检索知识库时，视为完全无权，直接返回空结果、不发检索请求。"""
    return bool(principal.dept) and bool(kb_scope)
