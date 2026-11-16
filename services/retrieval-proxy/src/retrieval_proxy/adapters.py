"""把既有后端客户端包装成 RetrievalBackend SPI（WK08）。

RAGFlow 侧沿用 metadata_condition 方言；WeKnora 侧见 weknora.py。
包装而非重写，是为了保留已验收的 RAGFlow 实现与测试作为对照跑分基准（决策 D3）。
"""

from __future__ import annotations

from .backends import BackendChunk, BackendError, BackendQuery
from .models import DEFAULT_TENANT
from .ragflow import RagflowClient, RagflowQuery


class RagflowBackend:
    """RAGFlow 适配器：SPI 请求 -> RagflowQuery -> RagflowClient。"""

    def __init__(self, client: RagflowClient) -> None:
        self._client = client

    def retrieve(self, query: BackendQuery) -> list[BackendChunk]:
        # RAGFlow 的 metadata_condition 没有 tenant 维度：多租户共享实例时，
        # 仅靠 dept/sensitivity/project 无法阻止跨租户泄露。在契约和写入侧
        # 补齐 tenant 之前，非默认租户一律 fail-closed。
        if query.principal.tenant != DEFAULT_TENANT:
            raise BackendError("RAGFlow 后端未启用多租户隔离")
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
