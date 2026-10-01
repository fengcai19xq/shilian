"""跨模块调用一律走 HTTP 客户端接口，不 import 其它模块的代码。测试用 fake 实现。"""

from __future__ import annotations

from typing import Any, Protocol

import httpx

from decide.models import Principal


class ProbeResult:
    """探测检索结果。只保留路由需要的信息，不透传越权文档内容。"""

    __slots__ = ("scope_desc", "top_score", "total")

    def __init__(self, top_score: float | None, total: int, scope_desc: str | None = None) -> None:
        self.top_score = top_score
        self.total = total
        self.scope_desc = scope_desc

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> ProbeResult:
        hits = payload.get("hits") or []
        scores = [float(h.get("score", 0.0)) for h in hits]
        return cls(
            top_score=max(scores) if scores else None,
            total=int(payload.get("total", len(hits))),
            scope_desc=payload.get("scope_desc"),
        )


class RetrievalClient(Protocol):
    """services/retrieval-proxy 的 HTTP 接口，契约见 contracts/retrieval.md。"""

    def probe(
        self,
        query: str,
        principal: Principal,
        kb_scope: list[str],
        top_k: int,
        score_threshold: float,
    ) -> ProbeResult: ...


class GatewayClient(Protocol):
    """services/gateway 的 HTTP 接口，契约见 contracts/gateway.md。"""

    def invoke(
        self,
        purpose: str,
        sensitivity: str,
        payload: dict[str, Any],
        principal: Principal,
        preferred_model: str | None = None,
    ) -> dict[str, Any]: ...


class HttpRetrievalClient:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def probe(
        self,
        query: str,
        principal: Principal,
        kb_scope: list[str],
        top_k: int,
        score_threshold: float,
    ) -> ProbeResult:
        body = {
            "query": query,
            "principal": principal.model_dump(mode="json"),
            "kb_scope": kb_scope,
            "top_k": top_k,
            "score_threshold": score_threshold,
            "mode": "probe",
        }
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.post(f"{self._base_url}/retrieval/search", json=body)
            resp.raise_for_status()
            return ProbeResult.from_payload(resp.json())


class HttpGatewayClient:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def invoke(
        self,
        purpose: str,
        sensitivity: str,
        payload: dict[str, Any],
        principal: Principal,
        preferred_model: str | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "purpose": purpose,
            "sensitivity": sensitivity,
            "principal": principal.model_dump(mode="json"),
            "payload": payload,
        }
        if preferred_model:
            body["preferred_model"] = preferred_model
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.post(f"{self._base_url}/gateway/invoke", json=body)
            resp.raise_for_status()
            return resp.json()
