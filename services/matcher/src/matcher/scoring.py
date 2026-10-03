"""打分与三态判定。

final_score = 0.35×recall_score + 0.50×p(satisfies_item) + 0.15×form_score
硬约束不通过的候选先被否决，final_score 记 0，不参与择优。
"""

from __future__ import annotations

from .config import ScoringConfig
from .constraints import check_hard_constraints
from .models import CandidateDoc, ChecklistItem, ItemResult, ItemStatus, ScoredCandidate
from .ports import DecideProvider


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def score_candidate(
    item: ChecklistItem,
    candidate: CandidateDoc,
    decide: DecideProvider,
    config: ScoringConfig,
) -> ScoredCandidate:
    reasons = check_hard_constraints(item, candidate)
    if reasons:
        # 硬约束先行：不调 decide，省 token 也避免高分翻盘
        return ScoredCandidate(
            doc_id=candidate.doc_id,
            doc_name=candidate.doc_name,
            uri=candidate.uri,
            rejected=True,
            reject_reasons=reasons,
            recall_score=candidate.recall_score,
            form_score=candidate.form_score,
            final_score=0.0,
        )

    result = decide.decide(item, candidate)
    p = _clamp(result.p)
    final = (
        config.w_recall * _clamp(candidate.recall_score)
        + config.w_decide * p
        + config.w_form * _clamp(candidate.form_score)
    )
    return ScoredCandidate(
        doc_id=candidate.doc_id,
        doc_name=candidate.doc_name,
        uri=candidate.uri,
        rejected=False,
        recall_score=candidate.recall_score,
        p_satisfies=p,
        form_score=candidate.form_score,
        final_score=round(final, 6),
        decide_reason=result.reason,
    )


def decide_status(score: float, config: ScoringConfig) -> ItemStatus:
    if score >= config.matched_threshold:
        return ItemStatus.MATCHED
    if score >= config.pending_threshold:
        return ItemStatus.PENDING
    return ItemStatus.MISSING


def score_item(
    item: ChecklistItem,
    candidates: list[CandidateDoc],
    decide: DecideProvider,
    config: ScoringConfig,
) -> ItemResult:
    scored = [score_candidate(item, c, decide, config) for c in candidates]
    passed = [s for s in scored if not s.rejected]
    scored.sort(key=lambda s: (not s.rejected, s.final_score), reverse=True)

    best = max(passed, key=lambda s: s.final_score, default=None)
    score = best.final_score if best else 0.0
    status = decide_status(score, config) if best else ItemStatus.MISSING
    bound = [best.doc_id] if best and status is ItemStatus.MATCHED else []

    note = ""
    if not passed and scored:
        note = "全部候选被硬约束否决：" + ",".join(sorted({r for s in scored for r in s.reject_reasons}))
    elif not scored:
        note = "库内无候选"
    if item.type_status == "unknown":
        note = (note + "；" if note else "") + "资料类型未在词典中，需人工补充词典"

    return ItemResult(
        item_id=item.item_id,
        no=item.no,
        std_type=item.std_type,
        std_name=item.std_name,
        status=status,
        score=score,
        candidates=scored,
        bound_doc_ids=bound,
        note=note,
    )
