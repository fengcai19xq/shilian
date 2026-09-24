"""测试公共夹具：假 RAGFlow 客户端（会返回越权文档）与内存审计。"""

from __future__ import annotations

import pytest

from retrieval_proxy.audit import InMemoryAuditSink
from retrieval_proxy.models import Principal
from retrieval_proxy.ragflow import RagflowChunk, RagflowQuery

# 测试数据全部为脱敏虚构数据
ALL_CHUNKS: list[RagflowChunk] = [
    RagflowChunk(
        chunk_id="c_1",
        doc_id="d_1",
        doc_name="融资部-研发费用说明.pdf",
        dept="融资部",
        sensitivity="L2",
        score=0.81,
        text="研发费用占营收比例……",
        page=3,
        project="PRJ-DEMO-A",
    ),
    RagflowChunk(
        chunk_id="c_2",
        doc_id="d_2",
        doc_name="财务共享-年度审计报告.pdf",
        dept="财务共享",
        sensitivity="L3",
        score=0.91,
        text="审计意见……",
        page=37,
        project="PRJ-DEMO-A",
    ),
    # 越权样本：部门不匹配
    RagflowChunk(
        chunk_id="c_3",
        doc_id="d_3",
        doc_name="人力资源部-高管薪酬明细.xlsx",
        dept="人力资源部",
        sensitivity="L2",
        score=0.95,
        text="薪酬明细……",
        page=1,
        project="PRJ-DEMO-A",
    ),
    # 越权样本：密级超上限
    RagflowChunk(
        chunk_id="c_4",
        doc_id="d_4",
        doc_name="融资部-并购意向书.pdf",
        dept="融资部",
        sensitivity="L4",
        score=0.93,
        text="并购条款……",
        page=2,
        project="PRJ-DEMO-A",
    ),
    # 越权样本：项目不在授权范围
    RagflowChunk(
        chunk_id="c_5",
        doc_id="d_5",
        doc_name="融资部-他项目投前测算.xlsx",
        dept="融资部",
        sensitivity="L2",
        score=0.88,
        text="投前测算……",
        page=5,
        project="PRJ-DEMO-B",
    ),
]

UNAUTHORIZED_DOC_NAMES = [
    "人力资源部-高管薪酬明细.xlsx",
    "融资部-并购意向书.pdf",
    "融资部-他项目投前测算.xlsx",
]


class FakeRagflowClient:
    """假 RAGFlow：记录收到的请求。

    `honor_condition=False` 时故意忽略 metadata_condition、返回包含越权文档的原始结果，
    用来验证返回体不泄露越权文档。
    """

    def __init__(self, chunks: list[RagflowChunk] | None = None, honor_condition: bool = True):
        self._chunks = list(ALL_CHUNKS if chunks is None else chunks)
        self._honor_condition = honor_condition
        self.calls: list[RagflowQuery] = []

    def retrieve(self, query: RagflowQuery) -> list[RagflowChunk]:
        self.calls.append(query)
        if not self._honor_condition:
            return list(self._chunks)
        return [c for c in self._chunks if self._match(c, query)][: query.top_k]

    @staticmethod
    def _match(chunk: RagflowChunk, query: RagflowQuery) -> bool:
        """按 metadata_condition 在“检索侧”过滤，模拟 RAGFlow 的行为。"""
        for cond in query.metadata_condition.get("conditions", []):
            value = {
                "dept": chunk.dept,
                "sensitivity": chunk.sensitivity,
                "project": chunk.project,
            }.get(cond["name"])
            if cond["comparison_operator"] == "in" and value not in cond["value"]:
                return False
        return True


@pytest.fixture
def audit() -> InMemoryAuditSink:
    return InMemoryAuditSink()


@pytest.fixture
def principal_l3() -> Principal:
    return Principal(
        user_id="u_10231",
        dept=["融资部", "财务共享"],
        roles=["financing_staff"],
        max_sensitivity="L3",
        projects=["PRJ-DEMO-A"],
    )


@pytest.fixture
def principal_no_access() -> Principal:
    return Principal(user_id="u_00001", dept=[], roles=[], max_sensitivity="L1", projects=[])
