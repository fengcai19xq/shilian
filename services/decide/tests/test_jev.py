"""JevDecider：gateway payload 构造、返回解析与降级路径。"""

from __future__ import annotations

import json

import httpx
import pytest
from fakes import FakeGateway, FakeRetrieval
from fastapi.testclient import TestClient

from decide.app import build_service, create_app
from decide.clients import HttpGatewayClient
from decide.config import Settings
from decide.deciders import DeciderError, JevDecider, build_decider, parse_decide_response


def test_jev_payload_follows_gateway_contract(principal):
    gateway = FakeGateway(
        {
            "model": "jev-decide",
            "switched_reason": None,
            "payload": {"need_internal_probability": 0.82, "reason": "涉及公司制度"},
            "usage": {"prompt_tokens": 40, "completion_tokens": 4, "cost_cny": 0.00002},
        }
    )
    result = JevDecider(gateway, sensitivity="L3").decide("报销流程", principal)
    call = gateway.calls[0]
    assert call["purpose"] == "decide"
    assert call["preferred_model"] == "jev-decide"
    assert call["sensitivity"] == "L3"
    assert call["payload"] == {"task": "need_internal_knowledge", "question": "报销流程"}
    assert result.need_internal_probability == 0.82
    assert result.reason == "涉及公司制度"
    assert result.model == "jev-decide"
    assert result.usage["cost_cny"] == 0.00002
    assert result.latency_ms is not None


@pytest.mark.parametrize(
    "response",
    [
        {"payload": {}},
        {"payload": {"need_internal_probability": 1.5}},
        {"payload": {"need_internal_probability": "high"}},
        {"payload": {"need_internal_probability": True}},
        {"payload": "oops"},
        {"payload": {"label": "maybe"}},
    ],
)
def test_parse_rejects_invalid_response(response):
    with pytest.raises(DeciderError) as exc:
        parse_decide_response(response)
    assert exc.value.kind == "invalid_response"


@pytest.mark.parametrize(
    ("gateway", "keyword"),
    [
        (FakeGateway(error=TimeoutError("t")), "超时"),
        (FakeGateway(error=httpx.ReadTimeout("t")), "超时"),
        (FakeGateway({"payload": {"need_internal_probability": 0.9}}, delay=0.5), "超时"),
        (FakeGateway(error=httpx.ConnectError("refused")), "调用 gateway 失败"),
        (FakeGateway({"payload": {"need_internal_probability": 9}}), "返回无效"),
    ],
)
def test_failures_fall_back_to_rule_with_reason(principal, gateway, keyword):
    decider = build_decider("jev", gateway, timeout_seconds=0.05)
    result = decider.decide("我们公司的合同审批流程", principal)
    assert result.backend == "rule"
    assert result.fallback_reason.startswith("jev ")
    assert keyword in result.fallback_reason
    assert "降级到纯规则" in result.fallback_reason
    assert result.need_internal_probability >= 0.5


def test_fallback_none_raises(principal):
    decider = build_decider("jev", FakeGateway(error=RuntimeError("x")), fallback="none")
    with pytest.raises(DeciderError):
        decider.decide("q", principal)


def test_fallback_reason_reaches_evidence_over_http():
    settings = Settings(decider_backend="jev", decider_timeout_seconds=0.05)
    gateway = FakeGateway({"payload": {"need_internal_probability": 0.1}}, delay=0.5)
    client = TestClient(create_app(build_service(settings, FakeRetrieval(top_score=None), gateway)))
    body = client.post(
        "/decide/route",
        json={"question": "我们公司的合同审批流程", "principal": {"user_id": "u_t"}},
    ).json()
    assert body["decision"] == "kb_empty"
    assert body["evidence"]["decider_backend"] == "rule"
    assert "超时" in body["evidence"]["decider_fallback_reason"]


def test_http_gateway_client_posts_v1_invoke(principal):
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"payload": {"need_internal_probability": 0.4}})

    client = HttpGatewayClient("http://gw.test/", transport=httpx.MockTransport(handler))
    result = JevDecider(client).decide("q", principal)
    assert seen["path"] == "/v1/invoke"
    body = seen["body"]
    assert body["purpose"] == "decide"
    assert body["preferred_model"] == "jev-decide"
    assert body["principal"] == {"user_id": principal.user_id, "dept": principal.dept}
    assert body["payload"]["question"] == "q"
    assert result.need_internal_probability == 0.4
