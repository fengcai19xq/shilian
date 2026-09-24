"""接口层单测：POST /retrieval/search 的入参与返回体符合契约。"""

from __future__ import annotations

from conftest import UNAUTHORIZED_DOC_NAMES, FakeRagflowClient
from fastapi.testclient import TestClient

from retrieval_proxy.app import create_app, get_service
from retrieval_proxy.audit import InMemoryAuditSink
from retrieval_proxy.service import RetrievalService

PRINCIPAL = {
    "user_id": "u_10231",
    "dept": ["融资部", "财务共享"],
    "roles": ["financing_staff"],
    "max_sensitivity": "L3",
    "projects": ["PRJ-DEMO-A"],
}


def _client(honor_condition: bool = True) -> tuple[TestClient, InMemoryAuditSink]:
    app = create_app()
    audit = InMemoryAuditSink()
    fake = FakeRagflowClient(honor_condition=honor_condition)
    app.dependency_overrides[get_service] = lambda: RetrievalService(fake, audit)
    return TestClient(app), audit


def test_检索接口返回契约字段() -> None:
    client, audit = _client()
    resp = client.post(
        "/retrieval/search",
        json={
            "query": "最近三年研发费用占营收比例",
            "principal": PRINCIPAL,
            "kb_scope": ["kb_finance", "kb_audit"],
            "top_k": 8,
            "score_threshold": 0.35,
            "mode": "full",
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert set(body) == {"hits", "scope_desc", "total"}
    assert body["total"] == 2
    assert body["scope_desc"] == "融资部 / 财务共享（你的权限内）"
    assert set(body["hits"][0]) == {
        "chunk_id",
        "doc_id",
        "doc_name",
        "dept",
        "sensitivity",
        "page",
        "score",
        "text",
    }
    assert len(audit.events) == 1


def test_接口层同样不泄露越权文档() -> None:
    client, _ = _client(honor_condition=False)
    resp = client.post(
        "/retrieval/search",
        json={"query": "薪酬", "principal": PRINCIPAL, "kb_scope": ["kb_hr"], "mode": "full"},
    )
    assert resp.status_code == 200
    for name in UNAUTHORIZED_DOC_NAMES:
        assert name not in resp.text


def test_非法密级入参被拒绝() -> None:
    client, _ = _client()
    bad = dict(PRINCIPAL, max_sensitivity="L9")
    resp = client.post(
        "/retrieval/search",
        json={"query": "x", "principal": bad, "kb_scope": ["kb_finance"]},
    )
    assert resp.status_code == 422
