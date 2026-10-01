"""HTTP 接口测试。"""

from __future__ import annotations

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
