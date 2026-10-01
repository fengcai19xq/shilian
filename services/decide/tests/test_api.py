"""HTTP 接口 POST /decide/route。"""

from __future__ import annotations

from fakes import FakeDecider, FakeGateway, FakeRetrieval
from fastapi.testclient import TestClient

from decide.app import build_service, create_app
from decide.config import Settings

PRINCIPAL = {
    "user_id": "u_test_001",
    "dept": ["融资部"],
    "roles": ["financing_staff"],
    "max_sensitivity": "L3",
    "projects": [],
}


def _client(retrieval, decider=None, gateway=None, settings=None) -> TestClient:
    service = build_service(
        settings=settings or Settings(),
        retrieval=retrieval,
        gateway=gateway or FakeGateway(),
        decider=decider,
    )
    return TestClient(create_app(service))


def test_route_kb_empty_over_http():
    client = _client(FakeRetrieval(top_score=0.1), FakeDecider(0.9))
    resp = client.post(
        "/decide/route",
        json={"question": "研发费用占营收比例", "principal": PRINCIPAL, "user_choice": "auto"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["decision"] == "kb_empty"
    assert body["confirm_required"] is True
    assert body["allow_general_fallback"] is False
    assert body["evidence"]["probe_top_score"] == 0.1
    assert body["evidence"]["need_internal_probability"] == 0.9


def test_route_default_choice_is_auto():
    client = _client(FakeRetrieval(top_score=0.9), FakeDecider(0.0))
    resp = client.post("/decide/route", json={"question": "研发费用", "principal": PRINCIPAL})
    assert resp.json()["decision"] == "enterprise"


def test_route_uses_configured_jev_backend():
    gateway = FakeGateway({"need_internal_probability": 0.1})
    client = _client(
        FakeRetrieval(top_score=None), gateway=gateway, settings=Settings(decider_backend="jev")
    )
    body = client.post(
        "/decide/route", json={"question": "随便问问", "principal": PRINCIPAL}
    ).json()
    assert body["decision"] == "general"
    assert body["evidence"]["decider_backend"] == "jev"
    assert len(gateway.calls) == 1


def test_route_rejects_invalid_payload():
    client = _client(FakeRetrieval())
    assert (
        client.post("/decide/route", json={"question": "", "principal": PRINCIPAL}).status_code
        == 422
    )
    assert (
        client.post(
            "/decide/route",
            json={"question": "q", "principal": PRINCIPAL, "user_choice": "bogus"},
        ).status_code
        == 422
    )
    assert client.post("/decide/route", json={"question": "q"}).status_code == 422


def test_healthz():
    assert _client(FakeRetrieval()).get("/healthz").json() == {"status": "ok"}
