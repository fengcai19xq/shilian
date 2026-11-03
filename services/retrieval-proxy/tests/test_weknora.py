"""WeKnora adapter 单测：下推参数、KB 名反解、标签收窄与零泄露。全部走 MockTransport。"""

from __future__ import annotations

import json

import httpx
import pytest

from retrieval_proxy.backends import BackendQuery
from retrieval_proxy.kb_encoding import build_scope, kb_name, project_tag
from retrieval_proxy.permissions import build_backend_scope
from retrieval_proxy.models import Principal
from retrieval_proxy.weknora import BackendError, WeKnoraClient

PRINCIPAL = Principal(
    user_id="u_10231",
    dept=["融资部", "财务共享"],
    roles=["financing_staff"],
    max_sensitivity="L3",
    projects=["PRJ-DEMO-A"],
    tenant="acme",
)


def _backend_query(principal: Principal = PRINCIPAL, *, rerank: bool = True, top_k: int = 8):
    scope = build_backend_scope(principal)
    return BackendQuery(
        question="最近三年研发费用占营收比例",
        kb_scope=["kb_finance"],
        top_k=top_k,
        similarity_threshold=0.35,
        rerank=rerank,
        principal=principal,
        visible_kb_names=scope.knowledge_base_ids,
        tag_names=scope.tag_names,
    )


# 已登记的 KB：两个部门 × L1..L3，外加一个越权 L4 库（不在可见范围，服务端也不该返回）
KB_ROWS = [
    {"name": kb_name("acme", "L1", "融资部"), "id": "kb-a1"},
    {"name": kb_name("acme", "L2", "融资部"), "id": "kb-a2"},
    {"name": kb_name("acme", "L3", "融资部"), "id": "kb-a3"},
    {"name": kb_name("acme", "L1", "财务共享"), "id": "kb-b1"},
    {"name": kb_name("acme", "L2", "财务共享"), "id": "kb-b2"},
    {"name": kb_name("acme", "L3", "财务共享"), "id": "kb-b3"},
    {"name": kb_name("acme", "L4", "融资部"), "id": "kb-a4"},  # 越权密级
    {"name": "other-no-slug", "id": "kb-x"},  # 名称不合规范，应被忽略
]
TAG_ROWS = [
    {"name": project_tag("PRJ-DEMO-A"), "id": "t-a"},
    {"name": project_tag(None), "id": "t-none"},
]


def _client(handler) -> WeKnoraClient:
    return WeKnoraClient("http://weknora.test", "k", tenant_id="10001",
                         transport=httpx.MockTransport(handler))


def _ok(data):
    return httpx.Response(200, json={"success": True, "data": data})


def _standard_handler(search_rows, seen: dict):
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/knowledge-bases":
            return _ok(KB_ROWS)
        if req.url.path.endswith("/tags"):
            return _ok(TAG_ROWS)
        if req.url.path == "/api/v1/knowledge-search":
            seen["body"] = json.loads(req.content)
            seen["auth"] = req.headers.get("x-api-key")
            seen["tenant"] = req.headers.get("x-tenant-id")
            return _ok(search_rows)
        raise AssertionError(req.url.path)

    return handler


def _row(chunk_id="c1", kb="kb-a3", doc="d_1", page=37, tag=None):
    r = {
        "id": chunk_id,
        "knowledge_id": doc,
        "knowledge_title": "财务共享-年度审计报告.pdf",
        "score": 0.91,
        "content": "审计意见……",
        "knowledge_base_id": kb,
        "source_locators": [{"type": "pdf", "page": page}],
    }
    if tag is not None:
        r["tags"] = [{"name": tag}]
    return r


# ---------------- 下推参数（红线 1） ----------------


def test_权限条件随检索请求下发且不发knowledge_ids():
    seen: dict = {}
    client = _client(_standard_handler([_row()], seen))
    client.retrieve(_backend_query())

    body = seen["body"]
    assert seen["auth"] == "k" and seen["tenant"] == "10001"
    # 密级+部门复合进 KB：L3 用户可见 2 部门 × (L1,L2,L3) 共 6 库，越权 L4 不在其中
    assert sorted(body["knowledge_base_ids"]) == sorted(
        ["kb-a1", "kb-a2", "kb-a3", "kb-b1", "kb-b2", "kb-b3"]
    )
    assert "kb-a4" not in body["knowledge_base_ids"]
    # project 走 tag：授权项目 + 哨兵
    assert sorted(body["tag_ids"]) == ["t-a", "t-none"]
    # 陷阱 1：knowledge_ids 绝不出现在权限请求里
    assert "knowledge_ids" not in body
    assert body["match_count"] == 8 and body["vector_threshold"] == 0.35
    assert "rerank" not in body  # full 模式沿用后端默认（开 rerank）


def test_无项目授权时不下发tag收窄():
    seen: dict = {}
    principal = Principal(user_id="u_1", dept=["融资部"], max_sensitivity="L1", projects=[],
                          tenant="acme")
    client = _client(_standard_handler([], seen))
    client.retrieve(_backend_query(principal))
    assert "tag_ids" not in seen["body"]


def test_probe模式关闭rerank并压低条数():
    seen: dict = {}
    client = _client(_standard_handler([], seen))
    client.retrieve(_backend_query(rerank=False, top_k=3))
    assert seen["body"]["rerank"] == {"enabled": False}
    assert seen["body"]["match_count"] == 3


# ---------------- 可见范围解析 ----------------


def test_可见KB全部解析不到时不发检索请求():
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/knowledge-bases":
            return _ok([])  # 空空间
        raise AssertionError("不应发起检索请求")

    assert _client(handler).retrieve(_backend_query()) == []


def test_需要标签收窄但标签解析不到时返回空():
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/knowledge-bases":
            return _ok(KB_ROWS)
        if req.url.path.endswith("/tags"):
            return _ok([])  # 一个标签都查不到
        raise AssertionError("不应发起检索请求")

    assert _client(handler).retrieve(_backend_query()) == []


def test_部门与密级从KB名反解且page与project从结果解析():
    seen: dict = {}
    rows = [
        _row("c1", kb="kb-a3", doc="d_1", page=37, tag=project_tag("PRJ-DEMO-A")),
        _row("c2", kb="kb-b1", doc="d_2", page=3, tag=project_tag(None)),
    ]
    chunks = _client(_standard_handler(rows, seen)).retrieve(_backend_query())

    by_id = {c.chunk_id: c for c in chunks}
    assert by_id["c1"].dept == "融资部" and by_id["c1"].sensitivity == "L3"
    assert by_id["c1"].page == 37 and by_id["c1"].project == "PRJ-DEMO-A"
    # 哨兵标签 = 未标注项目（与 RAGFlow 语义对齐）
    assert by_id["c2"].dept == "财务共享" and by_id["c2"].sensitivity == "L1"
    assert by_id["c2"].project is None


def test_结果落在未授权KB上直接丢弃():
    seen: dict = {}
    # 服务端多给了一个未授权 L4 库的行（模拟下推失效），adapter 层丢弃
    rows = [_row("c1", kb="kb-a3"), _row("c9", kb="kb-a4")]
    chunks = _client(_standard_handler(rows, seen)).retrieve(_backend_query())
    assert [c.chunk_id for c in chunks] == ["c1"]


def test_缺关键字段的行被丢弃且总数受topk截断():
    seen: dict = {}
    rows = [_row("c1"), {"id": "", "knowledge_id": "d", "content": "无id"},
            {"id": "c2", "knowledge_id": "d2", "content": "x", "knowledge_base_id": "kb-z"}]
    chunks = _client(_standard_handler(rows, seen)).retrieve(_backend_query(top_k=1))
    assert [c.chunk_id for c in chunks] == ["c1"]


# ---------------- 错误面 ----------------


def test_上游错误不透出响应体():
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/knowledge-bases":
            return _ok(KB_ROWS)
        if req.url.path.endswith("/tags"):
            return _ok(TAG_ROWS)
        return httpx.Response(500, json={"error": "泄密内容片段"})

    with pytest.raises(BackendError) as e:
        _client(handler).retrieve(_backend_query())
    assert "泄密" not in str(e.value)
    assert "500" in str(e.value)


def test_success_false_也报错且不回显详情():
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/knowledge-bases":
            return _ok(KB_ROWS)
        if req.url.path.endswith("/tags"):
            return _ok(TAG_ROWS)
        return httpx.Response(200, json={"success": False,
                                         "error": {"code": 1002, "message": "secret doc text"}})

    with pytest.raises(BackendError) as e:
        _client(handler).retrieve(_backend_query())
    assert "secret" not in str(e.value) and "1002" in str(e.value)


def test_名称解析结果缓存_二次检索不再拉列表():
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        return _standard_handler([_row()], {})(req)

    client = _client(handler)
    q = _backend_query()
    client.retrieve(q)
    before = len(calls)
    client.retrieve(q)
    # 第二次只有 knowledge-search，列表/标签索引已缓存
    assert len(calls) == before + 1
    assert calls[-1] == "/api/v1/knowledge-search"
