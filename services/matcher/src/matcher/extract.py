"""从清单文本中抽取硬约束：期间 / 口径 / 份数 / 盖章。

全部用正则与算术实现，**不调模型**（AGENTS.md 工程约定）。
"""

from __future__ import annotations

import datetime as _dt
import re

from .models import Constraints, Scope

_CN_DIGITS = {"零": 0, "一": 1, "两": 2, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}

_YEAR = re.compile(r"(19|20)\d{2}")
_YEAR_RANGE = re.compile(r"((?:19|20)\d{2})\s*(?:年度?)?\s*[-~—至到]\s*((?:19|20)\d{2})")
_RECENT_YEARS = re.compile(r"(?:最近|近|过去)\s*([0-9一二两三四五六七八九十]+)\s*(?:个)?年")
_COPIES = re.compile(r"(?:一式)?\s*([0-9一二两三四五六七八九十]+)\s*(?:份|套|本|册)")
_STAMP = re.compile(r"盖章|公章|骑缝章|加盖|签章")
_CONSOLIDATED = re.compile(r"合并")
_STANDALONE = re.compile(r"单体|母公司|本部|个别报表")


def _to_int(token: str) -> int | None:
    token = token.strip()
    if token.isdigit():
        return int(token)
    if token == "十":
        return 10
    if len(token) == 2 and token[0] == "十":  # 十一 ~ 十九
        return 10 + _CN_DIGITS.get(token[1], 0)
    if len(token) == 2 and token[1] == "十":  # 二十 ~ 九十
        return _CN_DIGITS.get(token[0], 0) * 10
    if len(token) == 1:
        return _CN_DIGITS.get(token)
    return None


def extract_periods(text: str, reference_year: int | None = None) -> list[str]:
    """抽取期间年份，返回升序去重的年份字符串列表。"""
    ref = reference_year or _dt.datetime.now(_dt.UTC).date().year
    years: set[str] = set()

    for start, end in _YEAR_RANGE.findall(text):
        a, b = int(start), int(end)
        if a <= b and b - a < 20:
            years.update(str(y) for y in range(a, b + 1))

    if not years:
        recent = _RECENT_YEARS.search(text)
        if recent:
            n = _to_int(recent.group(1))
            if n and 0 < n <= 20:
                years.update(str(y) for y in range(ref - n + 1, ref + 1))

    if not years:
        years.update(m.group(0) for m in _YEAR.finditer(text))

    return sorted(years)


def extract_scope(text: str) -> Scope | None:
    if _CONSOLIDATED.search(text):
        return Scope.CONSOLIDATED
    if _STANDALONE.search(text):
        return Scope.STANDALONE
    return None


def extract_copies(text: str) -> int | None:
    m = _COPIES.search(text)
    if not m:
        return None
    n = _to_int(m.group(1))
    return n if n and n > 0 else None


def extract_stamp_required(text: str) -> bool:
    return bool(_STAMP.search(text))


def extract_constraints(text: str, reference_year: int | None = None) -> Constraints:
    return Constraints(
        period=extract_periods(text, reference_year),
        scope=extract_scope(text),
        copies=extract_copies(text),
        stamp_required=extract_stamp_required(text),
    )
