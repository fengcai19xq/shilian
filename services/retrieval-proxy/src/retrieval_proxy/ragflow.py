"""RAGFlow 检索客户端抽象。

对外只依赖 `RagflowClient` 协议，真实实现与测试用 fake 都可注入。
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx


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
    """RAGFlow HTTP API 客户端（`POST /api/v1/retrieval`）。

    - 权限条件 `metadata_condition` 原样随检索请求下发（键名按 RAGFlow 约定转为 `logic`）
    - `kb_ids` 既可是数据集 id 也可是数据集名称，名称经 `GET /api/v1/datasets?name=` 解析并缓存
    - RAGFlow 检索结果不带文档级元数据，按 `(dataset_id, document_id)` 回查文档 `meta_fields` 并缓存；
      缺少 dept / sensitivity 的片段一律丢弃，宁可少召回也不放过越权风险
    - 密钥只从环境变量或构造参数读取，不在代码里硬编码
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        timeout: float = 30.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._http = httpx.Client(
            base_url=self._base_url,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=timeout,
            transport=transport,
        )
        self._dataset_ids: dict[str, str] = {}
        self._doc_meta: dict[tuple[str, str], dict[str, Any]] = {}

    @classmethod
    def from_env(cls) -> HttpRagflowClient:
        base_url = os.environ.get("RAGFLOW_BASE_URL", "")
        api_key = os.environ.get("RAGFLOW_API_KEY", "")
        if not base_url or not api_key:
            raise RuntimeError("缺少环境变量 RAGFLOW_BASE_URL / RAGFLOW_API_KEY")
        return cls(base_url, api_key)

    # ---- 检索 ----

    def retrieve(self, query: RagflowQuery) -> list[RagflowChunk]:
        dataset_ids = [self._resolve_dataset(k) for k in query.kb_ids]
        dataset_ids = [d for d in dataset_ids if d]
        if not dataset_ids:
            return []
        body: dict[str, Any] = {
            "question": query.question,
            "dataset_ids": dataset_ids,
            "page": 1,
            "page_size": query.top_k,
            "similarity_threshold": query.similarity_threshold,
            "metadata_condition": _to_ragflow_condition(query.metadata_condition),
        }
        if not query.rerank:
            body["rerank_candidates_count"] = max(query.top_k, 1)
        data = self._post("/api/v1/retrieval", body)
        chunks: list[RagflowChunk] = []
        for raw in data.get("chunks", []):
            chunk = self._to_chunk(raw)
            if chunk is not None:
                chunks.append(chunk)
        return chunks

    # ---- 内部 ----

    def _to_chunk(self, raw: dict[str, Any]) -> RagflowChunk | None:
        dataset_id = str(raw.get("dataset_id", ""))
        doc_id = str(raw.get("document_id", ""))
        meta = self._document_meta(dataset_id, doc_id)
        dept = meta.get("dept")
        sensitivity = meta.get("sensitivity")
        if not dept or not sensitivity:
            return None
        return RagflowChunk(
            chunk_id=str(raw.get("id", "")),
            doc_id=doc_id,
            doc_name=str(raw.get("document_keyword", "")),
            dept=str(dept),
            sensitivity=str(sensitivity),
            score=float(raw.get("similarity", 0.0)),
            text=str(raw.get("content", "")),
            page=_page_of(raw.get("positions")),
            project=(str(meta["project"]) if meta.get("project") else None),
        )

    def _document_meta(self, dataset_id: str, doc_id: str) -> dict[str, Any]:
        key = (dataset_id, doc_id)
        if key in self._doc_meta:
            return self._doc_meta[key]
        data = self._get(f"/api/v1/datasets/{dataset_id}/documents", {"id": doc_id, "page_size": 1})
        docs = data.get("docs", []) if isinstance(data, dict) else []
        meta = docs[0].get("meta_fields") or {} if docs else {}
        if not isinstance(meta, dict):
            meta = {}
        self._doc_meta[key] = meta
        return meta

    def _resolve_dataset(self, kb: str) -> str | None:
        if kb in self._dataset_ids:
            return self._dataset_ids[kb]
        data = self._get("/api/v1/datasets", {"name": kb, "page_size": 1})
        items = (
            data
            if isinstance(data, list)
            else data.get("datasets", [])
            if isinstance(data, dict)
            else []
        )
        if not items:
            # 非名称则按 id 直接使用（RAGFlow 会校验归属）
            resolved = kb if re.fullmatch(r"[0-9a-f]{32}", kb) else None
        else:
            resolved = str(items[0]["id"])
        if resolved:
            self._dataset_ids[kb] = resolved
        return resolved

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        resp = self._http.post(path, json=body)
        return _unwrap(resp)

    def _get(self, path: str, params: dict[str, Any]) -> Any:
        resp = self._http.get(path, params=params)
        return _unwrap(resp)


class RagflowError(RuntimeError):
    """RAGFlow 返回非 0 code 或 HTTP 错误。错误信息不含任何文档内容。"""


def _unwrap(resp: httpx.Response) -> Any:
    if resp.status_code >= 400:
        raise RagflowError(f"RAGFlow HTTP {resp.status_code}")
    payload = resp.json()
    if isinstance(payload, dict) and payload.get("code", 0) != 0:
        raise RagflowError(f"RAGFlow code {payload.get('code')}")
    return payload.get("data", {}) if isinstance(payload, dict) else payload


def _to_ragflow_condition(cond: dict[str, Any]) -> dict[str, Any]:
    """内部条件使用 `op`，RAGFlow 使用 `logic`；其余字段（name / comparison_operator / value）一致。"""
    if not cond:
        return {}
    out = dict(cond)
    if "op" in out and "logic" not in out:
        out["logic"] = out.pop("op")
    return out


def _page_of(positions: Any) -> int | None:
    """RAGFlow positions 为 [[page, x0, x1, y0, y1], ...]，页码从 1 起；取首个有效页。"""
    if not isinstance(positions, list):
        return None
    for pos in positions:
        if isinstance(pos, list) and pos and isinstance(pos[0], (int, float)):
            return int(pos[0])
    return None
