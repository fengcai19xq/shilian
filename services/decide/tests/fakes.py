"""测试用 fake：retrieval / gateway / decider，均不发真实网络请求。"""

from __future__ import annotations

from typing import Any

from decide.clients import ProbeResult
from decide.deciders import DecideResult
from decide.models import Principal


class FakeRetrieval:
    def __init__(
        self,
        top_score: float | None = None,
        total: int | None = None,
        scope_desc: str | None = "融资部 / 财务共享（你的权限内）",
        error: Exception | None = None,
    ) -> None:
        self.top_score = top_score
        self.total = total if total is not None else (0 if top_score is None else 1)
        self.scope_desc = scope_desc
        self.error = error
        self.calls: list[dict[str, Any]] = []

    def probe(
        self,
        query: str,
        principal: Principal,
        kb_scope: list[str],
        top_k: int,
        score_threshold: float,
    ) -> ProbeResult:
        self.calls.append(
            {
                "query": query,
                "principal": principal,
                "kb_scope": kb_scope,
                "top_k": top_k,
                "score_threshold": score_threshold,
            }
        )
        if self.error:
            raise self.error
        return ProbeResult(self.top_score, self.total, self.scope_desc)


class FakeDecider:
    def __init__(self, probability: float, name: str = "fake") -> None:
        self.probability = probability
        self.name = name
        self.calls: list[str] = []

    def decide(self, question: str, principal: Principal) -> DecideResult:
        self.calls.append(question)
        return DecideResult(need_internal_probability=self.probability, backend=self.name)


class FakeGateway:
    def __init__(
        self, response: dict[str, Any] | None = None, error: Exception | None = None
    ) -> None:
        self.response = response or {}
        self.error = error
        self.calls: list[dict[str, Any]] = []

    def invoke(
        self,
        purpose: str,
        sensitivity: str,
        payload: dict[str, Any],
        principal: Principal,
        preferred_model: str | None = None,
    ) -> dict[str, Any]:
        self.calls.append(
            {
                "purpose": purpose,
                "sensitivity": sensitivity,
                "payload": payload,
                "principal": principal,
                "preferred_model": preferred_model,
            }
        )
        if self.error:
            raise self.error
        return self.response
