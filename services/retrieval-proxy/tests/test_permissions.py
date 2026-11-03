"""权限翻译单测：密级展开与 metadata_condition 结构。"""

from __future__ import annotations

import pytest

from retrieval_proxy.kb_encoding import parse_project_tag, project_tag
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


# ---------------- project 标签可逆编码（回归：不可逆会静默丢弃已授权文档）----------------


@pytest.mark.parametrize("project", [
    "PRJ-DEMO-A",                # 大小写混合：小写化后与原值不等，正是缺陷触发点
    "PRJ-2026-CITIC",
    "中信银行授信",               # 中文
    "a",
    "with space",
    "weird::chars__",
])
def test_项目标签可逆往返(project: str) -> None:
    """encode → tag 名 → parse 必须拿回原值，否则兜底校验会把授权文档判成越权。"""
    tag = project_tag(project)
    assert tag.startswith("project::")
    assert parse_project_tag(tag) == project


def test_项目标签不丢失大小写():
    """显式钉死修复前后的差异点：PRJ-A 与 prj-a 必须编码成不同标签。"""
    assert project_tag("PRJ-A") != project_tag("prj-a")
    assert parse_project_tag(project_tag("PRJ-A")) == "PRJ-A"
    assert parse_project_tag(project_tag("prj-a")) == "prj-a"


def test_哨兵标签解析为空串():
    assert project_tag(None) == "project::__none__"
    assert parse_project_tag(project_tag(None)) == ""
    assert parse_project_tag("project::") == ""


def test_非项目标签返回None_业务标签不污染权限判定():
    assert parse_project_tag("dept:融资部") is None
    assert parse_project_tag("随便一个业务标签") is None


def test_未识别编码原样返回不打断检索():
    """前缀是 p 但长度非法（不是 4 的倍数）时退化为原串，不抛异常。"""
    assert parse_project_tag("project::p12") == "p12"
