"""MCP Server 端点测试（WK15）：端点鉴权、capability 最小化、权限保持。

不发真实网络请求：retrieval / gateway 都用 fake。
"""

from __future__ import annotations

import json
import os

import httpx
import pytest
from fastapi.testclient import TestClient

from gateway.api import create_app
from gateway.mcp_server import (
    McpEndpointConfig,
    McpEndpointRegistry,
    register_mcp_routes,
)


# ---------------- 测试夹具 ----------------


def make_registry(*endpoints: McpEndpointConfig) -> McpEndpointRegistry:
    return McpEndpointRegistry(endpoints={ep.endpoint_id: ep for ep in endpoints})


SEARCH_ONLY = McpEndpointConfig(
    endpoint_id="finance-search",
    token="mcp_sk_finance_001",
    kb_scope=("kb_finance",),
    principal={"user_id": "mcp_finance", "dept": ["融资部"], "max_sensitivity": "L2", "projects": []},
    capabilities=("search",),
)

SEARCH_AND_GEN = McpEndpointConfig(
    endpoint_id="audit-full",
    token="mcp_sk_audit_002",
    kb_scope=("kb_audit",),
    principal={"user_id": "mcp_audit", "dept": ["审计部"], "max_sensitivity": "L3", "projects": ["PRJ-2026"]},
    capabilities=("search", "generate"),
)


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def mcp_app(service):
    """挂载了 MCP 路由的 app，retrieval 用 httpx mock。"""
    registry = make_registry(SEARCH_ONLY, SEARCH_AND_GEN)
    app = create_app(service)
    # 用同进程 gateway_service 避免 HTTP 调用
    register_mcp_routes(
        app, registry,
        retrieval_base_url="http://mock-retrieval:8000",
        gateway_service=service,
    )
    return TestClient(app)


# ---------------- 端点鉴权 ----------------


def test_缺令牌返回401(mcp_app):
    r = mcp_app.get("/mcp/finance-search/tools")
    assert r.status_code == 401


def test_错令牌返回403(mcp_app):
    r = mcp_app.get("/mcp/finance-search/tools", headers=auth("wrong-token"))
    assert r.status_code == 403


def test_端点不存在返回404(mcp_app):
    r = mcp_app.get("/mcp/nonexistent/tools", headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 404


def test_端点令牌不能跨端点使用(mcp_app):
    # finance 的令牌不能访问 audit 的端点
    r = mcp_app.get("/mcp/audit-full/tools", headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 403


# ---------------- 工具列表（capability 最小化） ----------------


def test_只有search能力的端点只列search工具(mcp_app):
    r = mcp_app.get("/mcp/finance-search/tools", headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 200
    tools = r.json()["tools"]
    names = [t["name"] for t in tools]
    assert names == ["search"]


def test_有generate能力的端点列两个工具(mcp_app):
    r = mcp_app.get("/mcp/audit-full/tools", headers=auth("mcp_sk_audit_002"))
    assert r.status_code == 200
    tools = r.json()["tools"]
    names = sorted(t["name"] for t in tools)
    assert names == ["generate", "search"]


# ---------------- 检索 ----------------


def test_检索调用retrieval_proxy并透传principal(mcp_app, monkeypatch):
    captured = {}

    def fake_post(url, json=None, timeout=None, **kw):
        captured["url"] = url
        captured["body"] = json
        req = httpx.Request("POST", url)
        return httpx.Response(200, json={
            "hits": [{"doc_id": "d_1", "doc_name": "审计报告.pdf", "score": 0.9, "text": "片段", "page": 1}],
            "total": 1,
            "scope_desc": "审计部（你的权限内）",
        }, request=req)

    monkeypatch.setattr(httpx, "post", fake_post)
    r = mcp_app.post("/mcp/finance-search/search",
                     json={"query": "融资成本"}, headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 200
    assert r.json()["total"] == 1
    # 验证 principal 从端点配置注入，不是调用方传入
    assert captured["body"]["principal"]["dept"] == ["融资部"]
    assert captured["body"]["principal"]["max_sensitivity"] == "L2"
    # kb_scope 由端点配置决定
    assert captured["body"]["kb_scope"] == ["kb_finance"]


def test_检索结果原样透传(mcp_app, monkeypatch):
    def fake_post(url, json=None, timeout=None, **kw):
        req = httpx.Request("POST", url)
        return httpx.Response(200, json={"hits": [], "total": 0, "scope_desc": "无可检索范围"}, request=req)

    monkeypatch.setattr(httpx, "post", fake_post)
    r = mcp_app.post("/mcp/finance-search/search",
                     json={"query": "不存在的文档"}, headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 200
    assert r.json()["total"] == 0


def test_无search能力的端点不能检索(mcp_app):
    # 构造一个只有 generate 能力的端点
    gen_only = McpEndpointConfig(
        endpoint_id="gen-only",
        token="mcp_sk_gen_003",
        kb_scope=("kb_finance",),
        principal={"user_id": "mcp_gen", "dept": ["融资部"], "max_sensitivity": "L2", "projects": []},
        capabilities=("generate",),
    )
    registry = make_registry(gen_only)
    app = create_app()
    register_mcp_routes(app, registry, retrieval_base_url="http://mock:8000", gateway_service=None)
    c = TestClient(app)
    r = c.post("/mcp/gen-only/search", json={"query": "test"}, headers=auth("mcp_sk_gen_003"))
    assert r.status_code == 403


# ---------------- 生成 ----------------


def test_生成调用gateway并透传密级(mcp_app, service, factory):
    """generate 能力走同进程 gateway_service，验证密级路由生效。"""
    r = mcp_app.post("/mcp/audit-full/generate",
                     json={"query": "审计发现"}, headers=auth("mcp_sk_audit_002"))
    assert r.status_code == 200
    data = r.json()
    # 网关返回体必须有 model 和 usage（出账已走）
    assert "model" in data
    assert "usage" in data
    # 验证调用走了 gateway（factory 有调用记录）
    assert len(factory.calls) > 0
    call = factory.calls[-1]
    assert call.purpose.value == "generate"
    # payload.query 必须透传（契约 gateway.md 要求）
    assert call.payload.get("query") == "审计发现"


def test_无generate能力的端点不能生成(mcp_app):
    r = mcp_app.post("/mcp/finance-search/generate",
                     json={"query": "test"}, headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 403


# ---------------- 检索上游错误处理 ----------------


def test_检索上游错误只透出状态码不含文档内容(mcp_app, monkeypatch):
    def fake_post(url, json=None, timeout=None, **kw):
        req = httpx.Request("POST", url)
        return httpx.Response(500, text='{"error":"internal: d_2031 审计报告.pdf leak"}', request=req)

    monkeypatch.setattr(httpx, "post", fake_post)
    r = mcp_app.post("/mcp/finance-search/search",
                     json={"query": "test"}, headers=auth("mcp_sk_finance_001"))
    assert r.status_code == 502
    # 上游错误内容不透出（红线 2）
    assert "d_2031" not in r.text
    assert "审计报告" not in r.text


# ---------------- 环境变量解析 ----------------


def test_env解析端点配置(monkeypatch):
    config = json.dumps([
        {
            "endpoint_id": "test-ep",
            "token": "mcp_sk_test",
            "kb_scope": ["kb_finance"],
            "principal": {"user_id": "u_test", "dept": ["融资部"], "max_sensitivity": "L2", "projects": []},
            "capabilities": ["search"],
        }
    ])
    monkeypatch.setenv("MCP_ENDPOINTS", config)
    registry = McpEndpointRegistry.from_env()
    assert registry.enabled is True
    ep = registry.by_endpoint("test-ep")
    assert ep.kb_scope == ("kb_finance",)
    assert ep.has_capability("search") is True
    assert ep.has_capability("generate") is False


def test_env忽略缺kb_scope的端点(monkeypatch):
    config = json.dumps([
        {"endpoint_id": "bad", "token": "t", "principal": {"dept": ["融资部"]}},
    ])
    monkeypatch.setenv("MCP_ENDPOINTS", config)
    registry = McpEndpointRegistry.from_env()
    assert registry.enabled is False


def test_env忽略缺dept的端点(monkeypatch):
    config = json.dumps([
        {"endpoint_id": "bad", "token": "t", "kb_scope": ["kb_finance"],
         "principal": {"user_id": "u", "dept": [], "max_sensitivity": "L2"}},
    ])
    monkeypatch.setenv("MCP_ENDPOINTS", config)
    registry = McpEndpointRegistry.from_env()
    assert registry.enabled is False


def test_env空时不挂载路由():
    registry = McpEndpointRegistry.from_env()
    assert registry.enabled is False


def test_env非法JSON安全降级(monkeypatch):
    monkeypatch.setenv("MCP_ENDPOINTS", "not-json")
    registry = McpEndpointRegistry.from_env()
    assert registry.enabled is False


# ---------------- 默认关闭 ----------------


def test_未配置MCP端点时不挂载路由(service):
    """MCP_ENDPOINTS 未设时，/mcp/ 路由不应存在。"""
    app = create_app(service)
    # create_app 内部已尝试 from_env，环境无 MCP_ENDPOINTS → 不挂载
    c = TestClient(app)
    r = c.get("/mcp/any/tools")
    # 路由不存在 → 404（不是 401）
    assert r.status_code == 404
