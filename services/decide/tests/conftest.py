from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from decide.config import Settings
from decide.models import Principal


@pytest.fixture
def settings() -> Settings:
    return Settings(probe_score_threshold=0.55, need_internal_probability_threshold=0.5)


@pytest.fixture
def principal() -> Principal:
    # 脱敏测试数据
    return Principal(
        user_id="u_test_001",
        dept=["融资部", "财务共享"],
        roles=["financing_staff"],
        max_sensitivity="L3",
        projects=["PRJ-2026-TEST"],
    )
