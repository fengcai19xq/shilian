"""decide() 三种实现与配置切换。"""

from __future__ import annotations

import pytest
from fakes import FakeGateway

from decide.deciders import (
    FallbackDecider,
    JevDecider,
    LlmDecider,
    RuleDecider,
    build_decider,
)


def test_rule_decider_internal_question(principal):
    result = RuleDecider().decide("我们公司的差旅报销流程是什么", principal)
    assert result.backend == "rule"
    assert result.need_internal_probability >= 0.5


def test_rule_decider_general_question(principal):
    result = RuleDecider().decide("帮我写一首关于春天的诗", principal)
    assert result.need_internal_probability < 0.5


def test_rule_decider_no_signal_defaults_to_general(principal):
    assert RuleDecider().decide("你好", principal).need_internal_probability < 0.5


@pytest.mark.parametrize(("backend", "cls"), [("jev", JevDecider), ("llm", LlmDecider)])
def test_gateway_decider_calls_gateway_with_decide_purpose(principal, backend, cls):
    gateway = FakeGateway({"payload": {"need_internal_probability": 0.73}})
    decider = build_decider(backend, gateway)
    assert isinstance(decider, FallbackDecider)
    result = decider.decide("问题", principal)
    assert result.backend == backend
    assert result.need_internal_probability == 0.73
    call = gateway.calls[0]
    assert call["purpose"] == "decide"
    assert call["payload"] == {"task": "need_internal_knowledge", "question": "问题"}
    assert call["preferred_model"] == cls.preferred_model


def test_gateway_decider_accepts_label_only(principal):
    gateway = FakeGateway({"label": "internal"})
    assert JevDecider(gateway).decide("q", principal).need_internal_probability == 1.0
    gateway = FakeGateway({"label": "general"})
    assert JevDecider(gateway).decide("q", principal).need_internal_probability == 0.0


def test_gateway_failure_falls_back_to_rule(principal):
    decider = build_decider("jev", FakeGateway(error=TimeoutError("timeout")))
    result = decider.decide("我们公司的合同审批流程", principal)
    assert result.backend == "rule"
    assert result.fallback_reason and "jev" in result.fallback_reason
    assert result.need_internal_probability >= 0.5


@pytest.mark.parametrize("backend", ["rule", "", "unknown"])
def test_build_decider_defaults_to_rule(backend):
    assert isinstance(build_decider(backend, FakeGateway()), RuleDecider)


def test_build_decider_without_gateway_uses_rule():
    assert isinstance(build_decider("jev", None), RuleDecider)
