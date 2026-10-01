"""路由核心分支测试。"""

from __future__ import annotations

import json

import pytest
from fakes import FakeDecider, FakeRetrieval

from decide.models import RouteDecision, RouteRequest, UserChoice
from decide.routing import RoutingService

# 不含任何企业实体的问题，保证会走到探测 / 判定分支
NEUTRAL_Q = "最近三年研发费用占营收比例是多少"


def _svc(settings, retrieval, decider) -> RoutingService:
    return RoutingService(settings=settings, retrieval=retrieval, decider=decider)


def _req(principal, question=NEUTRAL_Q, choice=UserChoice.AUTO) -> RouteRequest:
    return RouteRequest(question=question, principal=principal, user_choice=choice)


# ---------- 最高优先级：kb_empty 绝不降级为 general ----------


@pytest.mark.parametrize("probability", [0.5, 0.51, 0.8, 1.0])
@pytest.mark.parametrize("top_score", [None, 0.0, 0.3, 0.5499])
def test_kb_empty_never_downgrades_to_general(settings, principal, probability, top_score):
    retrieval = FakeRetrieval(top_score=top_score)
    resp = _svc(settings, retrieval, FakeDecider(probability)).route(_req(principal))

    assert resp.decision is RouteDecision.KB_EMPTY
    assert resp.decision is not RouteDecision.GENERAL
    assert resp.confirm_required is True
    # 系统不得自行开放通用模型兜底，由前端二次确认后用户重新显式选择
    assert resp.allow_general_fallback is False
    assert "没有检索到" in resp.reason
    assert resp.evidence.need_internal_probability == probability


def test_kb_empty_when_retrieval_unavailable(settings, principal):
    """检索服务故障时也不能因为「没结果」就转通用模型。"""
    retrieval = FakeRetrieval(error=RuntimeError("retrieval down"))
    resp = _svc(settings, retrieval, FakeDecider(0.9)).route(_req(principal))
    assert resp.decision is RouteDecision.KB_EMPTY
    assert resp.confirm_required is True


# ---------- general 路径出参不含 principal 与公司字段 ----------


def test_general_response_contains_no_principal_or_company_fields(settings, principal):
    retrieval = FakeRetrieval(top_score=0.2, scope_desc="融资部 / 财务共享（你的权限内）")
    resp = _svc(settings, retrieval, FakeDecider(0.1)).route(
        _req(principal, question="帮我写一首关于春天的诗")
    )
    assert resp.decision is RouteDecision.GENERAL

    dumped = resp.model_dump(mode="json")
    text = json.dumps(dumped, ensure_ascii=False)
    assert "principal" not in dumped
    for value in [
        principal.user_id,
        *principal.dept,
        *principal.roles,
        *principal.projects,
        "user_id",
        "dept",
        "roles",
        "projects",
        "max_sensitivity",
        "融资部",
        "财务共享",
    ]:
        assert value not in text
    assert dumped["evidence"]["scope_desc"] is None
    assert dumped["evidence"]["matched_entities"] == []


def test_explicit_general_contains_no_company_fields(settings, principal):
    resp = _svc(settings, FakeRetrieval(), FakeDecider(0.9)).route(
        _req(principal, question="航锂 PRJ-2026-TEST 进度", choice=UserChoice.GENERAL)
    )
    text = json.dumps(resp.model_dump(mode="json"), ensure_ascii=False)
    assert resp.decision is RouteDecision.GENERAL
    for value in [principal.user_id, *principal.dept, *principal.projects, "航锂"]:
        assert value not in text


# ---------- 显式选择覆盖智能路由 ----------


def test_explicit_enterprise_overrides_smart_routing(settings, principal):
    retrieval = FakeRetrieval(top_score=0.0)
    decider = FakeDecider(0.0)
    resp = _svc(settings, retrieval, decider).route(
        _req(principal, question="帮我写一首诗", choice=UserChoice.ENTERPRISE)
    )
    assert resp.decision is RouteDecision.ENTERPRISE
    assert resp.evidence.source == "user_choice"
    assert retrieval.calls == [] and decider.calls == []


def test_explicit_general_overrides_entity_hit(settings, principal):
    retrieval = FakeRetrieval(top_score=0.99)
    decider = FakeDecider(1.0)
    resp = _svc(settings, retrieval, decider).route(
        _req(principal, question="融资部的用章管理办法", choice=UserChoice.GENERAL)
    )
    assert resp.decision is RouteDecision.GENERAL
    assert resp.evidence.source == "user_choice"
    assert retrieval.calls == [] and decider.calls == []


# ---------- 探测阈值边界 ----------


@pytest.mark.parametrize(
    ("top_score", "expected"),
    [
        (0.55, RouteDecision.ENTERPRISE),  # 恰好等于阈值 → 企业
        (0.5501, RouteDecision.ENTERPRISE),
        (0.5499, RouteDecision.GENERAL),  # 略低于阈值 → 进入判定，判定为通用
        (None, RouteDecision.GENERAL),  # 无命中
    ],
)
def test_probe_threshold_boundary(settings, principal, top_score, expected):
    decider = FakeDecider(0.1)
    resp = _svc(settings, FakeRetrieval(top_score=top_score), decider).route(_req(principal))
    assert resp.decision is expected
    assert resp.evidence.probe_threshold == 0.55
    assert resp.evidence.probe_top_score == top_score
    if expected is RouteDecision.ENTERPRISE:
        assert resp.evidence.source == "probe"
        assert decider.calls == []
    else:
        assert resp.evidence.source == "decide"
        assert len(decider.calls) == 1


def test_decide_probability_threshold_boundary(settings, principal):
    just_below = _svc(settings, FakeRetrieval(top_score=0.1), FakeDecider(0.4999)).route(
        _req(principal)
    )
    at = _svc(settings, FakeRetrieval(top_score=0.1), FakeDecider(0.5)).route(_req(principal))
    assert just_below.decision is RouteDecision.GENERAL
    assert at.decision is RouteDecision.KB_EMPTY


def test_probe_uses_probe_mode_parameters(settings, principal):
    retrieval = FakeRetrieval(top_score=0.9)
    req = RouteRequest(question=NEUTRAL_Q, principal=principal, kb_scope=["kb_finance"])
    resp = _svc(settings, retrieval, FakeDecider(0.0)).route(req)
    assert resp.decision is RouteDecision.ENTERPRISE
    assert resp.evidence.scope_desc == "融资部 / 财务共享（你的权限内）"
    call = retrieval.calls[0]
    assert call["top_k"] == 3
    assert call["kb_scope"] == ["kb_finance"]
    assert call["principal"] is principal


# ---------- 企业实体命中 ----------


@pytest.mark.parametrize(
    ("question", "entity"),
    [
        ("融资部今年的预算是多少", "融资部"),
        ("HL-280A 的循环寿命", "HL-280A"),
        ("PRJ-2026-TEST 的进度", "PRJ-2026-TEST"),
        ("差旅报销制度里住宿标准", "差旅报销制度"),
    ],
)
def test_entity_hit_routes_to_enterprise_without_probe(settings, principal, question, entity):
    retrieval = FakeRetrieval(top_score=0.0)
    decider = FakeDecider(0.0)
    resp = _svc(settings, retrieval, decider).route(_req(principal, question=question))
    assert resp.decision is RouteDecision.ENTERPRISE
    assert resp.evidence.source == "entity"
    assert entity in resp.evidence.matched_entities
    assert retrieval.calls == [] and decider.calls == []
