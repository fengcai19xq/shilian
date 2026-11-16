"""NL2SQL 翻译器：将自然语言提问翻译为指标 ID + 参数。

v1 使用关键词匹配（keyword matching），后续可替换为 LLM 翻译。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .dictionary import Indicator


@dataclass(frozen=True)
class NL2SQLResult:
    """翻译结果。"""

    indicator: Indicator | None
    params: dict[str, str]
    matched: bool
    reason: str


# 期间提取正则：YYYY、YYYY-QN、YYYY-MM、YYYY年
_PERIOD_PATTERNS = [
    (re.compile(r"(\d{4})[年\-/]Q([1-4])"), lambda m: f"{m.group(1)}-Q{m.group(2)}"),
    (re.compile(r"(\d{4})[年\-/](\d{1,2})月?"), lambda m: f"{m.group(1)}-{int(m.group(2)):02d}"),
    (re.compile(r"(\d{4})年?"), lambda m: m.group(1)),
]

# 同比增长率自动推断上期
_YOY_INDICATORS = {"revenue_yoy_growth"}


class KeywordTranslator:
    """关键词匹配翻译器。"""

    def __init__(self, indicators: list[Indicator]) -> None:
        self._indicators = indicators
        # 为每个指标建立关键词索引（用 name + description 的关键名词）
        self._keyword_map: dict[str, Indicator] = {}
        for ind in indicators:
            # 用 name 做主关键词
            self._keyword_map[ind.name] = ind
            # 用 id 做备选
            self._keyword_map[ind.id] = ind

    def translate(self, question: str) -> NL2SQLResult:
        """将自然语言提问翻译为指标 + 参数。"""
        # 1. 提取期间
        period = self._extract_period(question)

        # 2. 匹配指标
        matched_indicator: Indicator | None = None
        best_score = 0
        for ind in self._indicators:
            score = self._match_score(question, ind)
            if score > best_score:
                best_score = score
                matched_indicator = ind

        if matched_indicator is None or best_score == 0:
            return NL2SQLResult(
                indicator=None,
                params={},
                matched=False,
                reason="未匹配到任何已知指标，请尝试使用指标名称提问",
            )

        if not period:
            return NL2SQLResult(
                indicator=matched_indicator,
                params={},
                matched=False,
                reason=f"已识别指标「{matched_indicator.name}」，但未提取到期间，请指定年份或季度",
            )

        params: dict[str, str] = {"period": period}

        # 同比增长率需要 prev_period
        if matched_indicator.id in _YOY_INDICATORS:
            prev_period = self._derive_prev_period(period)
            if prev_period:
                params["prev_period"] = prev_period

        return NL2SQLResult(
            indicator=matched_indicator,
            params=params,
            matched=True,
            reason=f"已匹配指标「{matched_indicator.name}」，期间={period}",
        )

    def _extract_period(self, text: str) -> str | None:
        for pattern, formatter in _PERIOD_PATTERNS:
            m = pattern.search(text)
            if m:
                return formatter(m)
        return None

    def _derive_prev_period(self, period: str) -> str | None:
        # YYYY → YYYY-1
        if re.fullmatch(r"\d{4}", period):
            return str(int(period) - 1)
        # YYYY-QN → 同一季度的上年
        m = re.fullmatch(r"(\d{4})-Q([1-4])", period)
        if m:
            return f"{int(m.group(1)) - 1}-Q{m.group(2)}"
        # YYYY-MM → 上年同月
        m = re.fullmatch(r"(\d{4})-(\d{2})", period)
        if m:
            return f"{int(m.group(1)) - 1}-{m.group(2)}"
        return None

    def _match_score(self, question: str, indicator: Indicator) -> int:
        """简单的关键词匹配打分。"""
        q = question.lower()
        score = 0
        # 精确匹配 name
        if indicator.name in question:
            score += 10
        # id 子串匹配
        if indicator.id.lower() in q:
            score += 5
        # description 中的关键名词匹配（取前 4 个字）
        desc_keywords = indicator.description[:4]
        if desc_keywords and desc_keywords in question:
            score += 3
        return score
