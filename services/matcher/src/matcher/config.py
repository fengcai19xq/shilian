"""可配置项：打分权重、三态阈值、词典与列头配置路径。

阈值调参方向以降低假阳性为准（contracts/matching.md）。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parents[2] / "data"


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return float(raw)


@dataclass(frozen=True)
class ScoringConfig:
    """final_score = w_recall×recall + w_decide×p + w_form×form。"""

    w_recall: float = 0.35
    w_decide: float = 0.50
    w_form: float = 0.15
    matched_threshold: float = 0.85
    pending_threshold: float = 0.50

    @classmethod
    def from_env(cls) -> ScoringConfig:
        return cls(
            w_recall=_env_float("MATCHER_W_RECALL", 0.35),
            w_decide=_env_float("MATCHER_W_DECIDE", 0.50),
            w_form=_env_float("MATCHER_W_FORM", 0.15),
            matched_threshold=_env_float("MATCHER_MATCHED_THRESHOLD", 0.85),
            pending_threshold=_env_float("MATCHER_PENDING_THRESHOLD", 0.50),
        )


@dataclass(frozen=True)
class ParserConfig:
    """清单解析配置。

    column_aliases 为「字段 -> 列头别名」，默认读 data/columns.yaml，可整体替换。
    reference_year 用于「最近三年」这类相对期间的展开。
    """

    column_aliases: dict[str, list[str]] = field(default_factory=dict)
    reference_year: int | None = None
    max_header_scan_rows: int = 10


@dataclass(frozen=True)
class MatcherConfig:
    scoring: ScoringConfig = field(default_factory=ScoringConfig)
    parser: ParserConfig = field(default_factory=ParserConfig)
    types_path: Path = DATA_DIR / "types.yaml"
    columns_path: Path = DATA_DIR / "columns.yaml"
