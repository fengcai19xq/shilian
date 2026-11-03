"""把既有后端客户端包装成 RetrievalBackend SPI（WK08）。

RAGFlow 侧沿用 metadata_condition 方言；WeKnora 侧见 weknora.py。
包装而非重写，是为了保留已验收的 RAGFlow 实现与测试作为对照跑分基准（决策 D3）。
"""

from __future__ import annotations

from .backends import BackendChunk, BackendQuery
from .ragflow import RagflowClient, RagflowQuery


class RagflowBackend:
    """RAGFlow 适配器：SPI 请求 -> RagflowQuery -> RagflowClient。"""

    def __init__(self, client: RagflowClient) -> None:
        self._client = client

    def retrieve(self, query: BackendQuery) -> list[BackendChunk]:
        chunks = self._client.retrieve(
            RagflowQuery(
                question=query.question,
                kb_ids=list(query.kb_scope),
                top_k=query.top_k,
                similarity_threshold=query.similarity_threshold,
                rerank=query.rerank,
                metadata_condition=query.metadata_condition,
            )
        )
        # RagflowChunk 即 BackendChunk（同一 dataclass 别名），直接透传
        return list(chunks)
