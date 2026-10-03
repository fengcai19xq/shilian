"""硬约束与三态打分。"""

from __future__ import annotations

import pytest

from matcher.config import ScoringConfig
from matcher.constraints import (
    COPIES_INSUFFICIENT,
    PERIOD_MISMATCH,
    PERIOD_UNKNOWN,
    SCOPE_MISMATCH,
    STAMP_MISSING,
    TYPE_MISMATCH,
    check_hard_constraints,
)
from matcher.models import ItemStatus, Scope
from matcher.ports import FakeDecideProvider
from matcher.scoring import decide_status, score_candidate, score_item

from .conftest import make_doc, make_item

CFG = ScoringConfig()


def test_formula_weights():
    doc = make_doc(recall_score=0.8, form_score=0.6)
    s = score_candidate(make_item(), doc, FakeDecideProvider(default=0.9), CFG)
    assert s.final_score == pytest.approx(0.35 * 0.8 + 0.50 * 0.9 + 0.15 * 0.6)


@pytest.mark.parametrize(
    ("doc_kwargs", "reason"),
    [
        ({"period": ["2022"]}, PERIOD_MISMATCH),
        ({"period": []}, PERIOD_UNKNOWN),
        ({"scope": Scope.STANDALONE}, SCOPE_MISMATCH),
        ({"copies": 2}, COPIES_INSUFFICIENT),
        ({"stamped": False}, STAMP_MISSING),
        ({"std_type": "FINANCIAL_STATEMENT"}, TYPE_MISMATCH),
    ],
)
def test_each_hard_constraint_rejects(doc_kwargs, reason):
    assert reason in check_hard_constraints(make_item(), make_doc(**doc_kwargs))


def test_hard_constraint_veto_beats_high_score():
    """硬约束否决优先于高分：召回、形式、decide 全满分也必须 rejected，且不调 decide。"""
    decide = FakeDecideProvider(default=1.0)
    bad = make_doc(scope=Scope.STANDALONE, recall_score=1.0, form_score=1.0)
    s = score_candidate(make_item(), bad, decide, CFG)
    assert s.rejected and s.final_score == 0.0
    assert decide.calls == []

    result = score_item(make_item(), [bad], decide, CFG)
    assert result.status is ItemStatus.MISSING
    assert result.bound_doc_ids == []
    assert "scope_mismatch" in result.note


def test_period_2022_report_for_2023_item_rejected():
    item = make_item(raw_text="2023年度审计报告")
    doc = make_doc(doc_id="d_2022", doc_name="2022年度审计报告.pdf", period=["2022"])
    result = score_item(item, [doc], FakeDecideProvider(default=1.0), CFG)
    assert result.candidates[0].rejected
    assert PERIOD_MISMATCH in result.candidates[0].reject_reasons
    assert result.status is ItemStatus.MISSING


def test_rejected_high_score_does_not_outrank_valid_low_score():
    bad = make_doc(doc_id="bad", period=["2022"])
    ok = make_doc(doc_id="ok", recall_score=0.6, form_score=0.6)
    decide = FakeDecideProvider({"ok": 0.6})
    result = score_item(make_item(), [bad, ok], decide, CFG)
    assert result.candidates[0].doc_id == "ok"
    assert result.status is ItemStatus.PENDING
    assert result.bound_doc_ids == []  # pending 不自动绑定


@pytest.mark.parametrize(
    ("score", "status"),
    [
        (0.85, ItemStatus.MATCHED),
        (0.8499, ItemStatus.PENDING),
        (0.5, ItemStatus.PENDING),
        (0.4999, ItemStatus.MISSING),
        (0.0, ItemStatus.MISSING),
        (1.0, ItemStatus.MATCHED),
    ],
)
def test_three_state_boundaries(score, status):
    assert decide_status(score, CFG) is status


def test_three_state_boundary_via_formula():
    # recall=form=1.0 时 final = 0.5 + 0.5p，p=0.7 恰好 0.85
    item = make_item()
    hit = score_item(item, [make_doc()], FakeDecideProvider(default=0.7), CFG)
    assert hit.score == pytest.approx(0.85) and hit.status is ItemStatus.MATCHED
    assert hit.bound_doc_ids == ["d_2031"]
    below = score_item(item, [make_doc()], FakeDecideProvider(default=0.69), CFG)
    assert below.status is ItemStatus.PENDING


def test_thresholds_configurable():
    strict = ScoringConfig(matched_threshold=0.95, pending_threshold=0.7)
    assert decide_status(0.9, strict) is ItemStatus.PENDING
    assert decide_status(0.6, strict) is ItemStatus.MISSING


def test_no_candidates_is_missing():
    result = score_item(make_item(), [], FakeDecideProvider(), CFG)
    assert result.status is ItemStatus.MISSING and result.note == "库内无候选"


def test_unknown_type_item_noted():
    item = make_item(std_type="UNKNOWN", type_status="unknown", std_name=None)
    result = score_item(item, [], FakeDecideProvider(), CFG)
    assert result.status is ItemStatus.MISSING
    assert "词典" in result.note


def test_decide_probability_clamped():
    s = score_candidate(make_item(), make_doc(), FakeDecideProvider(default=7.0), CFG)
    assert s.p_satisfies == 1.0
