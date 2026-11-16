"""BFF 契约测试：身份只来自会话、客户端权限字段被剥离、密级不可下调。"""

from __future__ import annotations

import json
from dataclasses import replace

import httpx
from fastapi.testclient import TestClient

from bff.app import create_app
from bff.config import Settings

PRINCIPAL = {
    "user_id": "u_1",
    "dept": ["融资部"],
    "roles": ["financing_staff"],
    "max_sensitivity": "L3",
    "projects": ["PRJ-1"],
    "tenant": "default",
}


class Downstream:
    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.identity_status = 200
        self.identity_body = PRINCIPAL
        self.retrieval_status = 200
        self.retrieval_body = {"hits": [{"sensitivity": "L3"}], "scope_desc": "x", "total": 1}
        self.decide_status = 200
        self.decide_body = {
            "decision": "enterprise",
            "reason": "test",
            "confirm_required": False,
            "allow_general_fallback": False,
            "evidence": {"source": "probe"},
        }
        self.gateway_status = 200
        self.gateway_body = {
            "model": "m",
            "channel": "enterprise_api",
            "text": "answer",
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "cost_cny": 0.1},
        }
        self.error_body = {"detail": "secret 高管薪酬.xlsx"}

    async def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path == "/identity/me":
            return httpx.Response(self.identity_status, json=self.identity_body)
        if path == "/retrieval/search":
            return httpx.Response(self.retrieval_status, json=self.retrieval_body)
        if path == "/decide/route":
            return httpx.Response(self.decide_status, json=self.decide_body)
        if path == "/v1/invoke":
            return httpx.Response(self.gateway_status, json=self.gateway_body)
        if path.startswith("/ingest/documents"):
            return httpx.Response(202, json={"doc_id": "d_1"})
        if path == "/matcher/checklists":
            return httpx.Response(201, json={"checklist_id": "cl_1", "title": "清单", "items": []})
        if path == "/matcher/checklists/cl_1":
            return httpx.Response(200, json={"checklist_id": "cl_1", "title": "清单", "items": []})
        if path == "/matcher/checklists/cl_1/run":
            return httpx.Response(202, json={"task_id": "tk_1", "checklist_id": "cl_1", "state": "queued"})
        if path == "/matcher/tasks/tk_1":
            return httpx.Response(200, json={"task": {"taskId": "tk_1"}, "progress": 0.5})
        if path == "/matcher/items/it_1/confirm":
            return httpx.Response(200, json={"item_id": "it_1", "status": "matched"})
        if path == "/matcher/checklists/cl_1/manifest":
            return httpx.Response(200, json={"package_id": "pkg_1", "entries": []})
        if path == "/v1/packages":
            return httpx.Response(200, json={"package_id": "pkg_1", "artifacts": [],
                                              "missing_entries": [], "requires_manual_review": True})
        return httpx.Response(404, json={"detail": "not found"})

    def body_of(self, path: str) -> dict:
        for request in reversed(self.requests):
            if request.url.path == path:
                return json.loads(request.content)
        raise AssertionError(f"没有请求 {path}")

    def headers_of(self, path: str) -> httpx.Headers:
        for request in reversed(self.requests):
            if request.url.path == path:
                return request.headers
        raise AssertionError(f"没有请求 {path}")


def make_settings(**overrides) -> Settings:
    base = Settings(
        identity_url="http://identity",
        retrieval_url="http://retrieval",
        decide_url="http://decide",
        gateway_url="http://gateway",
        ingest_url="http://ingest",
        kb_scope=("kb_finance", "kb_audit"),
    )
    return replace(base, **overrides)


def make_client(downstream: Downstream, **settings_overrides) -> TestClient:
    settings = make_settings(**settings_overrides)
    http = httpx.AsyncClient(transport=httpx.MockTransport(downstream.handler))
    return TestClient(create_app(settings=settings, http_client=http))


def test_没有会话时拒绝检索且不调用下游() -> None:
    downstream = Downstream()
    client = make_client(downstream)
    resp = client.post("/retrieval/search", json={"query": "q", "kb_scope": ["kb"]})
    assert resp.status_code == 401
    assert downstream.requests == []


def test_默认不信任客户端自报的用户头() -> None:
    downstream = Downstream()
    client = make_client(downstream)
    resp = client.post(
        "/retrieval/search",
        headers={"X-User-Id": "u_1"},
        json={"query": "q"},
    )
    assert resp.status_code == 401
    assert downstream.requests == []


def test_客户端principal被丢弃_改用服务端解析的主体() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    tampered = {
        "query": "q",
        "principal": {
            "user_id": "attacker",
            "dept": ["人事部"],
            "max_sensitivity": "L4",
            "projects": ["SECRET"],
        },
        "kb_scope": ["evil_kb"],
    }
    resp = client.post("/retrieval/search", json=tampered)
    assert resp.status_code == 200
    body = downstream.body_of("/retrieval/search")
    assert body["principal"] == PRINCIPAL
    # kb_scope 由服务端配置覆盖，客户端不能扩大检索范围
    assert body["kb_scope"] == ["kb_finance", "kb_audit"]


def test_会话模式从identity换取principal() -> None:
    downstream = Downstream()
    client = make_client(downstream)
    resp = client.get("/identity/me", headers={"Authorization": "Bearer id-token"})
    assert resp.status_code == 200
    assert resp.json()["user_id"] == "u_1"
    assert downstream.headers_of("/identity/me")["authorization"] == "Bearer id-token"

    resp = client.post(
        "/decide/route",
        headers={"Authorization": "Bearer id-token"},
        json={"question": "q", "kb_scope": ["kb_finance"]},
    )
    assert resp.status_code == 200
    assert downstream.body_of("/decide/route")["principal"] == PRINCIPAL


def test_过渡模式只把用户标识交给身份服务换权限() -> None:
    downstream = Downstream()
    client = make_client(downstream, trust_user_header=True)
    resp = client.get("/identity/me", headers={"X-User-Id": "u_1"})
    assert resp.status_code == 200
    assert downstream.headers_of("/identity/me")["x-user-id"] == "u_1"


def test_会话Cookie只转发给身份服务() -> None:
    downstream = Downstream()
    client = make_client(downstream)
    resp = client.get("/identity/me", headers={"Cookie": "sid=abc"})
    assert resp.status_code == 200
    assert downstream.headers_of("/identity/me")["cookie"] == "sid=abc"

    assert client.post("/retrieval/search", json={"query": "q"}, headers={"Cookie": "sid=abc"}).status_code == 200
    # 业务服务不需要 Cookie，BFF 也不应把会话凭据扩散过去
    assert "cookie" not in downstream.headers_of("/retrieval/search")


def test_会话Cookie中的id_token转为Authorization() -> None:
    downstream = Downstream()
    client = make_client(downstream)
    resp = client.get("/identity/me", headers={"Cookie": "shilian_id_token=tok-123"})
    assert resp.status_code == 200
    headers = downstream.headers_of("/identity/me")
    assert headers["authorization"] == "Bearer tok-123"
    assert headers["cookie"] == "shilian_id_token=tok-123"


def test_身份服务失效时返回503而不是匿名() -> None:
    downstream = Downstream()
    downstream.identity_status = 500
    client = make_client(downstream)
    resp = client.get("/identity/me", headers={"Authorization": "Bearer id-token"})
    assert resp.status_code == 503


def test_企业生成必须已有检索上下文且密级不可下调() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )

    first = client.post(
        "/gateway/v1/invoke",
        json={
            "purpose": "generate",
            "sensitivity": "L1",
            "preferred_model": "deepseek-v3",
            "payload": {"query": "q", "messages": [{"role": "user", "content": "ctx"}]},
        },
    )
    assert first.status_code == 409
    assert downstream.requests == []

    assert client.post("/retrieval/search", json={"query": "q"}).status_code == 200
    second = client.post(
        "/gateway/v1/invoke",
        json={
            "purpose": "generate",
            "sensitivity": "L1",
            "preferred_model": "deepseek-v3",
            "payload": {"query": "q", "messages": [{"role": "user", "content": "ctx"}]},
        },
    )
    assert second.status_code == 200
    body = downstream.body_of("/v1/invoke")
    # 检索片段是 L3，客户端请求 L1 必须被抬到 L3
    assert body["sensitivity"] == "L3"
    assert body["principal"] == PRINCIPAL


def test_签名上下文Cookie可跨实例校验() -> None:
    settings = dict(
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
        context_secret="test-context-secret",
    )
    first_downstream = Downstream()
    first = make_client(first_downstream, **settings)
    retrieval = first.post("/retrieval/search", json={"query": "q"})
    assert retrieval.status_code == 200
    cookie = retrieval.cookies.get("shilian_ctx")
    assert cookie
    assert "HttpOnly" in retrieval.headers["set-cookie"]
    assert "SameSite=Lax" in retrieval.headers["set-cookie"]

    # 新实例没有内存上下文，只能靠签名 Cookie 恢复密级下限
    second_downstream = Downstream()
    second = make_client(second_downstream, **settings)
    second.cookies.set("shilian_ctx", cookie)
    resp = second.post(
        "/gateway/v1/invoke",
        json={
            "purpose": "generate",
            "sensitivity": "L1",
            "payload": {"query": "q", "messages": [{"role": "user", "content": "ctx"}]},
        },
    )
    assert resp.status_code == 200
    assert second_downstream.body_of("/v1/invoke")["sensitivity"] == "L3"


def test_篡改签名上下文Cookie被拒绝() -> None:
    settings = dict(
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
        context_secret="test-context-secret",
    )
    first = make_client(Downstream(), **settings)
    retrieval = first.post("/retrieval/search", json={"query": "q"})
    cookie = retrieval.cookies.get("shilian_ctx")
    tampered = cookie[:-4] + "0000"

    second = make_client(Downstream(), **settings)
    second.cookies.set("shilian_ctx", tampered)
    resp = second.post(
        "/gateway/v1/invoke",
        json={
            "purpose": "generate",
            "sensitivity": "L1",
            "payload": {"query": "q", "messages": [{"role": "user", "content": "ctx"}]},
        },
    )
    assert resp.status_code == 409


def test_畸形上下文Cookie不会500() -> None:
    settings = dict(
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
        context_secret="test-context-secret",
    )
    client = make_client(Downstream(), **settings)
    client.cookies.set("shilian_ctx", "not-a-valid-cookie")
    resp = client.post(
        "/gateway/v1/invoke",
        json={
            "purpose": "generate",
            "sensitivity": "L1",
            "payload": {"query": "q", "messages": [{"role": "user", "content": "ctx"}]},
        },
    )
    assert resp.status_code == 409


def test_通用生成不带principal且固定L1() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    resp = client.post(
        "/gateway/v1/invoke",
        json={
            "purpose": "generate",
            "sensitivity": "L4",
            "preferred_model": "deepseek-v3",
            "principal": {"user_id": "attacker", "dept": ["人事部"]},
            "payload": {"query": "怎么写列表推导式"},
        },
    )
    assert resp.status_code == 200
    body = downstream.body_of("/v1/invoke")
    assert "principal" not in body
    assert body["sensitivity"] == "L1"
    assert downstream.headers_of("/v1/invoke")["x-user-id"] == "u_1"


def test_入库代理要求管理员角色() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    assert client.get("/ingest/documents").status_code == 403
    assert downstream.requests == []


def test_管理员可以访问入库代理且注入用户头() -> None:
    downstream = Downstream()
    admin = dict(PRINCIPAL, roles=["it_admin"])
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(admin, ensure_ascii=False),
    )
    resp = client.get("/ingest/documents")
    assert resp.status_code == 202
    assert downstream.headers_of("/ingest/documents")["x-user-id"] == "u_1"


def test_下游错误不回显响应体() -> None:
    downstream = Downstream()
    downstream.retrieval_status = 500
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    resp = client.post("/retrieval/search", json={"query": "q"})
    assert resp.status_code == 502
    assert "secret" not in resp.text
    assert "高管薪酬" not in resp.text


def test_转发切换原因与预算告警响应头() -> None:
    downstream = Downstream()
    original = downstream.handler

    async def handler(request: httpx.Request) -> httpx.Response:
        resp = await original(request)
        if request.url.path == "/v1/invoke":
            resp.headers["X-Shilian-Switched-Reason"] = "%E6%B5%8B%E8%AF%95"
            resp.headers["X-Shilian-Budget-Alert"] = "85%25"
        return resp

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    settings = make_settings(
        dev_mode=True, dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False)
    )
    client = TestClient(create_app(settings=settings, http_client=http))
    resp = client.post(
        "/gateway/v1/invoke",
        json={"purpose": "generate", "sensitivity": "L1", "payload": {"query": "q"}},
    )
    assert resp.status_code == 200
    assert resp.headers["X-Shilian-Switched-Reason"] == "%E6%B5%8B%E8%AF%95"
    assert resp.headers["X-Shilian-Budget-Alert"] == "85%25"


def test_matcher_run用会话主体覆盖客户端principal() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    tampered = {"principal": {"user_id": "attacker", "dept": ["人事部"],
                              "max_sensitivity": "L4", "projects": ["X"]}}
    resp = client.post("/matcher/checklists/cl_1/run", json=tampered)
    assert resp.status_code == 202
    body = downstream.body_of("/matcher/checklists/cl_1/run")
    assert body["principal"] == PRINCIPAL
    assert downstream.headers_of("/matcher/checklists/cl_1/run")["x-user-id"] == "u_1"


def test_matcher_confirm注入操作人() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    resp = client.post("/matcher/items/it_1/confirm", json={"doc_ids": ["d_1"], "note": "ok"})
    assert resp.status_code == 200
    assert downstream.headers_of("/matcher/items/it_1/confirm")["x-user-id"] == "u_1"


def test_package只使用matcher生成的manifest() -> None:
    downstream = Downstream()
    client = make_client(
        downstream,
        dev_mode=True,
        dev_principal=json.dumps(PRINCIPAL, ensure_ascii=False),
    )
    # 客户端即使塞了恶意 manifest 也不会被转发
    resp = client.post(
        "/matcher/checklists/cl_1/package",
        json={"entries": [{"files": [{"uri": "local://docs/secret"}]}]},
    )
    assert resp.status_code == 200
    body = downstream.body_of("/v1/packages")
    assert body["package_id"] == "pkg_1"
    assert "secret" not in json.dumps(body, ensure_ascii=False)
