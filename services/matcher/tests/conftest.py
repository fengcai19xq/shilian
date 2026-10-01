"""测试公共夹具：全部使用假实现（fake），不访问任何真实系统。测试数据均为虚构。"""

from __future__ import annotations

import pytest

from matcher.config import MatcherConfig, ParserConfig
from matcher.models import CandidateDoc, ChecklistItem, Constraints, Scope
from matcher.ports import FakeDecideProvider, StaticCandidateProvider
from matcher.service import MatcherService


def make_item(**overrides) -> ChecklistItem:
    data = {
        "item_id": "it_0031",
        "checklist_id": "cl_0007",
        "no": "3.1",
        "raw_text": "2023年度审计报告（合并口径，加盖公章，3份）",
        "std_type": "AUDIT_REPORT",
        "type_status": "resolved",
        "std_name": "审计报告",
        "constraints": Constraints(period=["2023"], scope=Scope.CONSOLIDATED, copies=3,
                                   stamp_required=True),
    }
    data.update(overrides)
    return ChecklistItem(**data)


def make_doc(**overrides) -> CandidateDoc:
    data = {
        "doc_id": "d_2031",
        "doc_name": "2023年度审计报告.pdf",
        "uri": "oss://docs/d_2031.pdf",
        "std_type": "AUDIT_REPORT",
        "period": ["2023"],
        "scope": Scope.CONSOLIDATED,
        "copies": 3,
        "stamped": True,
        "recall_score": 1.0,
        "form_score": 1.0,
    }
    data.update(overrides)
    return CandidateDoc(**data)


@pytest.fixture
def config() -> MatcherConfig:
    return MatcherConfig(parser=ParserConfig(reference_year=2023))


@pytest.fixture
def decide() -> FakeDecideProvider:
    return FakeDecideProvider(default=1.0)


@pytest.fixture
def candidates() -> StaticCandidateProvider:
    return StaticCandidateProvider()


@pytest.fixture
def service(config, decide, candidates) -> MatcherService:
    return MatcherService(candidates=candidates, decide=decide, config=config)
