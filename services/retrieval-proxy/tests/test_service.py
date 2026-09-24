"""检索服务单测：检索前过滤、零泄露、probe 模式、无权限、审计。"""

from __future__ import annotations

import json

from conftest import UNAUTHORIZED_DOC_NAMES, FakeRagflowClient

from retrieval_proxy.audit import InMemoryAuditSink
from retrieval_proxy.models import Principal, SearchRequest
from retrieval_proxy.service import RetrievalService


def _request(principal: Principal, **kwargs) -> SearchRequest:
    payload = {
        "query": "最近三年研发费用占营收比例",
        "principal": principal,
        "kb_scope": ["kb_finance", "kb_audit"],
        "top_k": 8,
        "score_threshold": 0.35,
        "mode": "full",
    }
    payload.update(kwargs)
    return SearchRequest(**payload)


def test_过滤条件随检索请求下发而非事后过滤(
    principal_l3: Principal, audit: InMemoryAuditSink
) -> None:
    client = FakeRagflowClient()
    RetrievalService(client, audit).search(_request(principal_l3))

    assert len(client.calls) == 1
    cond = client.calls[0].metadata_condition
    by_name = {c["name"]: c["value"] for c in cond["conditions"]}
    assert by_name["dept"] == ["融资部", "财务共享"]
    assert by_name["sensitivity"] == ["L1", "L2", "L3"]
    assert by_name["project"] == ["PRJ-DEMO-A"]


def test_越权文档零泄露_即使下游忽略过滤条件(
    principal_l3: Principal, audit: InMemoryAuditSink
) -> None:
    # fake 故意忽略 metadata_condition，返回含越权文档的原始结果
    client = FakeRagflowClient(honor_condition=False)
    response = RetrievalService(client, audit).search(_request(principal_l3))

    body = json.dumps(response.model_dump(), ensure_ascii=False)
    for name in UNAUTHORIZED_DOC_NAMES:
        assert name not in body
    assert {h.doc_id for h in response.hits} == {"d_1", "d_2"}
    assert response.total == 2
    # 审计事件也不得记录越权文档
    assert audit.events[0].hit_doc_ids == ["d_1", "d_2"]


def test_密级上限正确展开_L3用户可见L1到L3不可见L4(
    principal_l3: Principal, audit: InMemoryAuditSink
) -> None:
    response = RetrievalService(FakeRagflowClient(), audit).search(_request(principal_l3))
    assert {h.sensitivity for h in response.hits} <= {"L1", "L2", "L3"}
    assert "d_4" not in {h.doc_id for h in response.hits}


def test_L1用户看不到L2及以上(audit: InMemoryAuditSink) -> None:
    principal = Principal(user_id="u_2", dept=["融资部"], max_sensitivity="L1")
    response = RetrievalService(FakeRagflowClient(), audit).search(_request(principal))
    assert response.total == 0
    assert response.hits == []


def test_probe模式_topk压到3且跳过rerank且只返回最高分(
    principal_l3: Principal, audit: InMemoryAuditSink
) -> None:
    client = FakeRagflowClient()
    response = RetrievalService(client, audit).search(_request(principal_l3, mode="probe", top_k=8))

    call = client.calls[0]
    assert call.top_k == 3
    assert call.rerank is False
    assert response.total == 1
    assert response.hits[0].doc_id == "d_2"  # 分数最高的有权文档


def test_full模式保留rerank与请求topk(
    principal_l3: Principal, audit: InMemoryAuditSink
) -> None:
    client = FakeRagflowClient()
    RetrievalService(client, audit).search(_request(principal_l3, top_k=5))
    assert client.calls[0].top_k == 5
    assert client.calls[0].rerank is True


def test_无权限时返回空且不发检索请求也不泄露存在性(
    principal_no_access: Principal, audit: InMemoryAuditSink
) -> None:
    client = FakeRagflowClient(honor_condition=False)
    response = RetrievalService(client, audit).search(_request(principal_no_access))

    assert client.calls == []
    assert response.total == 0
    assert response.hits == []
    body = json.dumps(response.model_dump(), ensure_ascii=False)
    for name in UNAUTHORIZED_DOC_NAMES:
        assert name not in body
    assert "无权" not in body and "存在" not in body


def test_kb_scope为空同样视为无权(principal_l3: Principal, audit: InMemoryAuditSink) -> None:
    client = FakeRagflowClient()
    response = RetrievalService(client, audit).search(_request(principal_l3, kb_scope=[]))
    assert client.calls == []
    assert response.total == 0


def test_每次检索写一条审计事件(principal_l3: Principal, audit: InMemoryAuditSink) -> None:
    service = RetrievalService(FakeRagflowClient(), audit)
    service.search(_request(principal_l3))
    service.search(_request(principal_l3, mode="probe"))

    assert len(audit.events) == 2
    first = audit.events[0]
    assert first.who == "u_10231"
    assert first.query == "最近三年研发费用占营收比例"
    assert first.scope == ["kb_finance", "kb_audit"]
    assert first.hit_doc_ids == ["d_1", "d_2"]
    assert first.ts is not None
    assert audit.events[1].mode == "probe"
