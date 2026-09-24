"""权限翻译单测：密级展开与 metadata_condition 结构。"""

from __future__ import annotations

import pytest

from retrieval_proxy.models import Principal
from retrieval_proxy.permissions import build_metadata_condition, expand_sensitivity


@pytest.mark.parametrize(
    ("max_sensitivity", "expected"),
    [
        ("L1", ["L1"]),
        ("L2", ["L1", "L2"]),
        ("L3", ["L1", "L2", "L3"]),
        ("L4", ["L1", "L2", "L3", "L4"]),
    ],
)
def test_密级上限展开为可见集合(max_sensitivity: str, expected: list[str]) -> None:
    assert expand_sensitivity(max_sensitivity) == expected


def test_未知密级报错() -> None:
    with pytest.raises(ValueError):
        expand_sensitivity("L9")


def test_metadata_condition_包含部门密级项目三类条件(principal_l3: Principal) -> None:
    cond = build_metadata_condition(principal_l3)
    assert cond["op"] == "and"
    by_name = {c["name"]: c for c in cond["conditions"]}
    assert by_name["dept"]["value"] == ["融资部", "财务共享"]
    assert by_name["sensitivity"]["value"] == ["L1", "L2", "L3"]
    assert by_name["project"]["value"] == ["PRJ-DEMO-A"]
    assert all(c["comparison_operator"] == "in" for c in cond["conditions"])


def test_无项目授权时不下发项目条件() -> None:
    principal = Principal(user_id="u_1", dept=["融资部"], max_sensitivity="L2")
    names = {c["name"] for c in build_metadata_condition(principal)["conditions"]}
    assert names == {"dept", "sensitivity"}
