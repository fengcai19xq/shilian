"""RAGFlow 检索客户端抽象。

对外只依赖 `RagflowClient` 协议，真实实现与测试用 fake 都可注入。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass(frozen=True)
class RagflowQuery:
    """下发给 RAGFlow 的检索请求（权限条件已经在 metadata_condition 里）。"""

    question: str
    kb_ids: list[str]
    top_k: int
    similarity_threshold: float
    rerank: bool
    metadata_condition: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RagflowChunk:
    """RAGFlow 返回的原始片段。"""

    chunk_id: str
    doc_id: str
    doc_name: str
    dept: str
    sensitivity: str
    score: float
    text: str
    page: int | None = None
    project: str | None = None


class RagflowClient(Protocol):
    """检索客户端接口；实现方负责把 metadata_condition 原样下发给 RAGFlow。"""

    def retrieve(self, query: RagflowQuery) -> list[RagflowChunk]:  # pragma: no cover - 协议声明
        ...


class HttpRagflowClient:
    """真实 RAGFlow HTTP 客户端占位实现。

    密钥一律读环境变量，不在代码里硬编码；具体接入待 RAGFlow 实例就绪后补齐。
    """

    def __init__(self, base_url: str, api_key: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key

    def retrieve(self, query: RagflowQuery) -> list[RagflowChunk]:
        raise NotImplementedError("RAGFlow 实例接入待 T05 完成后补齐")
