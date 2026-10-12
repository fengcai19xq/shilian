"""HttpRagflowClient：请求体映射、数据集名称解析、文档元数据回查与越权兜底。全部走 httpx MockTransport，不访问网络。"""

from __future__ import annotations

import json

import httpx
import pytest

from retrieval_proxy.ragflow import HttpRagflowClient, RagflowError, RagflowQuery

DS_ID = "0123456789abcdef0123456789abcdef"


def _client(handler) -> HttpRagflowClient:
    return HttpRagflowClient("http://ragflow.test", "k", transport=httpx.MockTransport(handler))


def _ok(data):
    return httpx.Response(200, json={"code": 0, "data": data})


def test_retrieve_maps_request_and_metadata():
    seen: dict[str, object] = {}

    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/datasets":
            assert req.url.params["name"] == "kb_finance"
            return _ok([{"id": DS_ID, "name": "kb_finance"}])
        if req.url.path == "/api/v1/retrieval":
            seen["body"] = json.loads(req.content)
            assert req.headers["authorization"] == "Bearer k"
            return _ok(
                {
                    "chunks": [
                        {
                            "id": "c1",
                            "document_id": "d1",
                            "dataset_id": DS_ID,
                            "document_keyword": "a.pdf",
                            "content": "研发费用……",
                            "similarity": 0.8,
                            "positions": [[3, 1, 1, 1, 1]],
                        },
                        {
                            "id": "c2",
                            "document_id": "d2",
                            "dataset_id": DS_ID,
                            "document_keyword": "b.pdf",
                            "content": "无元数据",
                            "similarity": 0.9,
                        },
                    ],
                    "total": 2,
                }
            )
        if req.url.path == f"/api/v1/datasets/{DS_ID}/documents":
            doc_id = req.url.params["id"]
            meta = (
                {"dept": "融资部", "sensitivity": "L2", "project": "PRJ-DEMO-A"}
                if doc_id == "d1"
                else {}
            )
            return _ok({"docs": [{"id": doc_id, "meta_fields": meta}], "total_datasets": 1})
        raise AssertionError(req.url)

    cond = {
        "op": "and",
        "conditions": [{"name": "dept", "comparison_operator": "in", "value": ["融资部"]}],
    }
    chunks = _client(handler).retrieve(
        RagflowQuery(
            question="q",
            kb_ids=["kb_finance"],
            top_k=5,
            similarity_threshold=0.3,
            rerank=True,
            metadata_condition=cond,
        )
    )
    body = seen["body"]
    assert body["dataset_ids"] == [DS_ID]
    assert body["page_size"] == 5
    assert body["metadata_condition"] == {"logic": "and", "conditions": cond["conditions"]}
    # 缺 dept / sensitivity 元数据的片段被丢弃
    assert [c.chunk_id for c in chunks] == ["c1"]
    assert chunks[0].dept == "融资部" and chunks[0].sensitivity == "L2"
    assert chunks[0].page == 3 and chunks[0].project == "PRJ-DEMO-A"


def test_unknown_dataset_name_returns_empty_without_retrieval():
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.url.path)
        return _ok([])

    assert (
        _client(handler).retrieve(
            RagflowQuery(
                question="q", kb_ids=["kb_nope"], top_k=3, similarity_threshold=0.3, rerank=False
            )
        )
        == []
    )
    assert "/api/v1/retrieval" not in calls


def test_probe_disables_rerank_and_errors_raise():
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.path == "/api/v1/datasets":
            return _ok([{"id": DS_ID}])
        return httpx.Response(200, json={"code": 102, "message": "boom"})

    with pytest.raises(RagflowError) as e:
        _client(handler).retrieve(
            RagflowQuery(
                question="q", kb_ids=[DS_ID], top_k=3, similarity_threshold=0.3, rerank=False
            )
        )
    assert "boom" not in str(e.value)
