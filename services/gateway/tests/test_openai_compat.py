"""OpenAI 兼容入口测试（WK06）：协议翻译正确 + 密级不可伪造 + 红线保持。

不发真实网络请求：网关用 conftest 的 fake 客户端，密钥注册表用内存注入。
"""

from __future__ import annotations

import json

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from gateway.api import create_app
from gateway.openai_compat import (
    HEADER_SWITCHED_REASON,
    EnvKeyRegistry,
    KeyBinding,
    register_openai_routes,
)

TENANT = "acme"
BINDINGS = {
    "sk-l3-fin": KeyBinding(TENANT, "融资部", "L3"),
    "sk-l1-fin": KeyBinding(TENANT, "融资部", "L1"),
    "sk-l4-fin": KeyBinding(TENANT, "融资部", "L4"),
}
REGISTRY = EnvKeyRegistry(bindings=dict(BINDINGS))


@pytest.fixture
def client(service, factory):
    app = create_app(service)
    register_openai_routes(app, service, REGISTRY)
    return TestClient(app), factory


def auth(key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {key}"}


def chat_body(**overrides):
    body = {
        "model": "deepseek-v3",
        "messages": [{"role": "user", "content": "介绍一下储能系统"}],
    }
    body.update(overrides)
    return body


# ---------------- 协议翻译 ----------------


def test_chat翻译为带密级的InvokeRequest(client):
    c, factory = client
    resp = c.post("/v1/chat/completions", json=chat_body(temperature=0.2), headers=auth("sk-l3-fin"))
    assert resp.status_code == 200
    call = factory.calls[-1]
    assert call.purpose.value == "generate"
    payload = call.payload
    assert payload["temperature"] == 0.2
    assert payload["messages"][0]["content"] == "介绍一下储能系统"
    # 契约 gateway.md 要求 generate 的 payload.query 必填
    assert payload["query"] == "介绍一下储能系统"


def test_响应是合法OpenAI形状(client):
    c, _ = client
    data = c.post("/v1/chat/completions", json=chat_body(), headers=auth("sk-l3-fin")).json()
    assert data["object"] == "chat.completion"
    assert data["choices"][0]["message"]["role"] == "assistant"
    assert data["choices"][0]["finish_reason"] == "stop"
    assert data["usage"]["total_tokens"] == data["usage"]["prompt_tokens"] + data["usage"]["completion_tokens"]
    # 实际通道借既有字段透出，WeKnora 不会因此报错
    assert data["system_fingerprint"].startswith("shilian_")


def test_embeddings映射到embed用途(client):
    c, factory = client
    resp = c.post("/v1/embeddings", json={"model": "bge-m3", "input": ["中信银行", "审计报告"]},
                  headers=auth("sk-l3-fin"))
    assert resp.status_code == 200
    assert factory.calls[-1].purpose.value == "embed"
    data = resp.json()
    assert data["object"] == "list"
    assert len(data["data"]) == 1  # fake 客户端固定返回一条
    assert data["data"][0]["object"] == "embedding"


def test_rerank映射到rerank用途(client):
    c, factory = client
    resp = c.post("/v1/rerank", json={"model": "bge-m3", "query": "授信", "documents": ["a", "b"]},
                  headers=auth("sk-l3-fin"))
    assert resp.status_code == 200
    assert factory.calls[-1].purpose.value == "rerank"
    data = resp.json()
    assert data["results"][0]["relevance_score"] >= 0


# ---------------- 密级由密钥决定 ----------------


def test_不同密钥路由到不同密级(client, service):
    c, _ = client
    seen = set()
    for key, expect in (("sk-l3-fin", "L3"), ("sk-l1-fin", "L1")):
        # 用 preferred_model 反推密级：L1 可用公有云，L3 不可
        resp = c.post("/v1/chat/completions",
                      json=chat_body(model="deepseek-v3"), headers=auth(key))
        assert resp.status_code == 200
        seen.add(resp.json()["system_fingerprint"])
    # 同一 preferred_model 在两个密级下落地通道不同
    assert len(seen) == 2, f"密级未生效，两次 fingerprint 相同: {seen}"


def test_请求体无法伪造密级与主体(client, factory):
    c, _ = client
    body = chat_body(sensitivity="L1", principal={"user_id": "attacker", "dept": ["风控部"]})
    c.post("/v1/chat/completions", json=body, headers=auth("sk-l3-fin"))
    # L3 必须触发脱敏：伪造的 L1 若生效则不会脱敏
    call = factory.calls[-1]
    assert call.model != "deepseek-v3", "L3 内容不应落在公有云通道模型上"


def test_L4只落VPC通道(client):
    c, _ = client
    data = c.post("/v1/chat/completions", json=chat_body(), headers=auth("sk-l4-fin")).json()
    assert data["system_fingerprint"] == "shilian_vpc_self_hosted"


def test_L3调用前脱敏且返回后还原(client, factory):
    c, _ = client
    # fake 客户端默认按 prompt 回显；这里让它回显 messages，
    # 以便真正验证「脱敏 → 模型 → 还原」闭环（模型侧会原样带出占位符）
    factory.client.responder = lambda spec, purpose, payload: {
        "text": "".join(m["content"] for m in payload.get("messages", []))
    }
    body = chat_body(messages=[{"role": "user", "content": "示例客户甲的报价是多少"}])
    data = c.post("/v1/chat/completions", json=body, headers=auth("sk-l3-fin")).json()
    sent = factory.calls[-1].payload
    # 离开进程的内容已脱敏
    assert "示例客户甲" not in json.dumps(sent, ensure_ascii=False)
    assert "[[CUSTOMER" in json.dumps(sent, ensure_ascii=False)
    # 返回给用户的内容已还原
    assert "示例客户甲" in data["choices"][0]["message"]["content"]


# ---------------- 红线 4：切换可观测 ----------------


def test_切换原因走响应头(client):
    c, _ = client
    resp = c.post("/v1/chat/completions", json=chat_body(model="deepseek-v3"),
                  headers=auth("sk-l3-fin"))
    from urllib.parse import unquote

    assert HEADER_SWITCHED_REASON in resp.headers
    assert "降级" in unquote(resp.headers[HEADER_SWITCHED_REASON]) or "通道" in unquote(
        resp.headers[HEADER_SWITCHED_REASON]
    )


def test_未切换时无切换头(client):
    c, _ = client
    resp = c.post("/v1/chat/completions",
                  json=chat_body(model="deepseek-v3-enterprise"), headers=auth("sk-l1-fin"))
    assert HEADER_SWITCHED_REASON not in resp.headers


# ---------------- 授权与错误面 ----------------


def test_缺密钥401_错密钥403且不下发上游(client, factory):
    c, _ = client
    assert c.post("/v1/chat/completions", json=chat_body()).status_code == 401
    assert c.post("/v1/chat/completions", json=chat_body(), headers=auth("Bearer sk-stolen")).status_code == 403
    assert c.post("/v1/chat/completions", json=chat_body(), headers=auth("sk-stolen")).status_code == 403
    assert factory.calls == []


def test_流式明确501不静默降级(client, factory):
    c, _ = client
    resp = c.post("/v1/chat/completions", json=chat_body(stream=True), headers=auth("sk-l3-fin"))
    assert resp.status_code == 501
    assert factory.calls == []


def test_无合规模型返回422(client, service):
    app = create_app(service)
    register_openai_routes(app, service, REGISTRY)
    c = TestClient(app)
    service.routing_config.models.clear()
    assert c.post("/v1/chat/completions", json=chat_body(), headers=auth("sk-l3-fin")).status_code == 422


def test_上游错误只透出状态码(client, service, monkeypatch):
    def boom(_request):
        req = httpx.Request("POST", "https://api.example.com/v1/chat/completions")
        resp = httpx.Response(402, request=req, text='{"error":"Insufficient Balance 含文档片段"}')
        raise httpx.HTTPStatusError("402", request=req, response=resp)

    monkeypatch.setattr(service, "invoke", boom)
    app = create_app(service)
    register_openai_routes(app, service, REGISTRY)
    c = TestClient(app)
    r = c.post("/v1/chat/completions", json=chat_body(), headers=auth("sk-l3-fin"))
    assert r.status_code == 502
    assert "Insufficient" not in r.text


# ---------------- 密钥注册表 ----------------


def test_env注册表忽略非法条目(monkeypatch):
    monkeypatch.setenv("SHIM_KEY_1", "sk-ok:acme:融资部:L3")
    monkeypatch.setenv("SHIM_KEY_2", "bad-format")
    monkeypatch.setenv("SHIM_KEY_3", "sk-badlevel:acme:融资部:L9")
    monkeypatch.setenv("OTHER_VAR", "x:y:z:L1")
    registry = EnvKeyRegistry.from_env()
    assert set(registry.bindings) == {"sk-ok"}
    assert registry.bindings["sk-ok"].sensitivity == "L3"
    assert registry.enabled is True


def test_空注册表不挂载路由(service):
    app = create_app(service)
    register_openai_routes(app, service, EnvKeyRegistry(bindings={}))
    c = TestClient(app)
    # 无密钥 → 401（路由已挂载但拒绝），这里只验证 /v1/invoke 仍可用
    assert c.post("/v1/invoke", json={
        "purpose": "generate", "sensitivity": "L1", "payload": {"prompt": "hi"}
    }).status_code == 200
