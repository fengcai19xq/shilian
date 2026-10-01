"""问答路由：显式选择优先，auto 时走「实体 → 探测检索 → decide 判定」三级智能路由。

红线：库内未命中必须如实告知（kb_empty）并由用户二次确认，**任何情况下都不自动转通用模型**。
"""

from __future__ import annotations

from decide.clients import ProbeResult, RetrievalClient
from decide.config import Settings
from decide.deciders import Decider, normalize_question
from decide.entities import EntityMatcher
from decide.models import (
    RouteDecision,
    RouteEvidence,
    RouteRequest,
    RouteResponse,
    UserChoice,
)


class RoutingService:
    def __init__(
        self,
        settings: Settings,
        retrieval: RetrievalClient,
        decider: Decider,
        entity_matcher: EntityMatcher | None = None,
    ) -> None:
        self._settings = settings
        self._retrieval = retrieval
        self._decider = decider
        self._entities = entity_matcher or EntityMatcher(
            settings.entity_vocab, settings.entity_patterns
        )

    def route(self, request: RouteRequest) -> RouteResponse:
        question = normalize_question(request.question)

        # 1. 显式选择优先，直接覆盖智能路由
        if request.user_choice is UserChoice.ENTERPRISE:
            return RouteResponse(
                decision=RouteDecision.ENTERPRISE,
                reason="用户显式选择企业知识库",
                evidence=RouteEvidence(source="user_choice"),
            )
        if request.user_choice is UserChoice.GENERAL:
            return _general_response(
                reason="用户显式选择通用模型",
                evidence=RouteEvidence(source="user_choice"),
            )

        # 2. 企业实体命中直接走企业知识路径，省掉一次检索
        matched = self._entities.match(question)
        if matched:
            return RouteResponse(
                decision=RouteDecision.ENTERPRISE,
                reason="问题中命中企业实体，走企业知识库",
                evidence=RouteEvidence(source="entity", matched_entities=matched),
            )

        # 3. 探测检索（mode=probe、top_k=3、不 rerank）
        probe = self._probe(question, request)
        threshold = self._settings.probe_score_threshold
        top_score = probe.top_score
        if top_score is not None and top_score >= threshold:
            return RouteResponse(
                decision=RouteDecision.ENTERPRISE,
                reason="探测检索命中企业知识库内容",
                evidence=RouteEvidence(
                    source="probe",
                    probe_top_score=top_score,
                    probe_total=probe.total,
                    probe_threshold=threshold,
                    scope_desc=probe.scope_desc,
                ),
            )

        # 4. 低于阈值 → 判定「是否需要公司内部资料才能回答」
        verdict = self._decider.decide(question, request.principal)
        evidence = RouteEvidence(
            source="decide",
            probe_top_score=top_score,
            probe_total=probe.total,
            probe_threshold=threshold,
            need_internal_probability=verdict.need_internal_probability,
            decider_backend=verdict.backend,
            decider_fallback_reason=verdict.fallback_reason,
            scope_desc=probe.scope_desc,
        )
        if verdict.need_internal_probability >= self._settings.need_internal_probability_threshold:
            # 需要内部资料却没检索到：如实告知，绝不自动降级到通用模型
            return RouteResponse(
                decision=RouteDecision.KB_EMPTY,
                reason=(
                    "这个问题需要公司内部资料才能回答，但在你的权限范围内没有检索到相关内容。"
                    "是否改用通用模型回答（回答将不包含公司资料）？"
                ),
                evidence=evidence,
                confirm_required=True,
                allow_general_fallback=False,
            )

        return _general_response(reason="问题不依赖公司内部资料，走通用模型", evidence=evidence)

    def _probe(self, question: str, request: RouteRequest) -> ProbeResult:
        try:
            return self._retrieval.probe(
                query=question,
                principal=request.principal,
                kb_scope=request.kb_scope,
                top_k=self._settings.probe_top_k,
                score_threshold=self._settings.probe_score_threshold,
            )
        except Exception:  # noqa: BLE001
            # 检索不可用时按「未命中」处理，后续判定决定是 kb_empty 还是 general
            return ProbeResult(top_score=None, total=0, scope_desc=None)


def _general_response(reason: str, evidence: RouteEvidence) -> RouteResponse:
    """通用模型路径：出参里剥掉一切公司相关信息（命中实体、检索范围）。"""
    sanitized = evidence.model_copy(update={"matched_entities": [], "scope_desc": None})
    return RouteResponse(
        decision=RouteDecision.GENERAL,
        reason=reason,
        evidence=sanitized,
        confirm_required=False,
        allow_general_fallback=True,
    )
