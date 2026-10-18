"""HTTP 接口测试。"""

from __future__ import annotations

import httpx
from fastapi.testclient import TestClient

from gateway.api import create_app


def _body(**overrides):
    body = {
        "purpose": "generate",
        "sensitivity": "L3",
        "preferred_model": "deepseek-v3",
        "principal": {"user_id": "u_10231", "dept": ["融资部"]},
        "payload": {"prompt": "示例客户甲的报价是多少"},
    }
    body.update(overrides)
    return body


def test_invoke_返回体符合契约(service, factory):
    client = TestClient(create_app(service))
    resp = client.post("/v1/invoke", json=_body())
    assert resp.status_code == 200
    data = resp.json()
    assert data["model"] == "deepseek-v3-enterprise"
    assert data["switched_reason"]
    assert set(data["usage"]) == {"prompt_tokens", "completion_tokens", "cost_cny"}
    assert "示例客户甲" not in factory.calls[-1].payload["prompt"]
    assert "示例客户甲" in data["output"]["text"]


def test_非法密级返回422(service):
    client = TestClient(create_app(service))
    assert client.post("/v1/invoke", json=_body(sensitivity="L9")).status_code == 422


def test_无可用模型返回422(service):
    service.routing_config.models.clear()
    client = TestClient(create_app(service))
    resp = client.post("/v1/invoke", json=_body())
    assert resp.status_code == 422


def test_缺密钥返回503(routing_config, masking_config, monkeypatch):
    from gateway.service import GatewayService

    for spec in routing_config.models.values():
        monkeypatch.delenv(spec.api_key_env, raising=False)
    client = TestClient(create_app(GatewayService(routing_config, masking_config)))
    assert client.post("/v1/invoke", json=_body()).status_code == 503


def test_healthz(service):
    assert TestClient(create_app(service)).get("/healthz").json() == {"status": "ok"}


def test_上游HTTP错误返回502且不透出上游响应体(service, monkeypatch):
    def boom(_request):
        req = httpx.Request("POST", "https://api.example.com/v1/chat/completions")
        resp = httpx.Response(402, request=req, text='{"error":"Insufficient Balance"}')
        raise httpx.HTTPStatusError("402", request=req, response=resp)

    monkeypatch.setattr(service, "invoke", boom)
    client = TestClient(create_app(service))
    r = client.post("/v1/invoke", json=_body())
    assert r.status_code == 502
    assert "402" in r.json()["detail"]
    assert "Insufficient" not in r.text


def test_principal可选_缺省取X_User_Id头或匿名(service):
    client = TestClient(create_app(service))
    body = _body(sensitivity="L1")
    body.pop("principal")
    assert client.post("/v1/invoke", json=body, headers={"X-User-Id": "u_hdr"}).status_code == 200
    assert client.post("/v1/invoke", json=body).status_code == 200
