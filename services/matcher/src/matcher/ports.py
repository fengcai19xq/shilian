"""对外依赖的可注入接口与假实现。

真实环境里 decide 走 services/decide、召回走 services/retrieval-proxy；
本模块只依赖这两个协议，测试里注入 fake，不触真实系统、不持密钥。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from .models import CandidateDoc, ChecklistItem


@dataclass(frozen=True)
class DecideResult:
    """p 为「该候选满足该清单项」的概率，取值 [0, 1]。"""

    p: float
    reason: str = ""


class DecideProvider(Protocol):
    def decide(self, item: ChecklistItem, candidate: CandidateDoc) -> DecideResult:  # pragma: no cover - 协议
        ...


class CandidateProvider(Protocol):
    def recall(self, item: ChecklistItem) -> list[CandidateDoc]:  # pragma: no cover - 协议
        ...


class FakeDecideProvider:
    """测试与本地调试用：按 doc_id 给定概率，未指定用 default。"""

    def __init__(self, probabilities: dict[str, float] | None = None, default: float = 0.5):
        self.probabilities = probabilities or {}
        self.default = default
        self.calls: list[tuple[str, str]] = []

    def decide(self, item: ChecklistItem, candidate: CandidateDoc) -> DecideResult:
        self.calls.append((item.item_id, candidate.doc_id))
        p = self.probabilities.get(candidate.doc_id, self.default)
        return DecideResult(p=p, reason="fake")


class StaticCandidateProvider:
    """测试与本地调试用：按 item_id 或 std_type 返回固定候选。"""

    def __init__(self, by_item: dict[str, list[CandidateDoc]] | None = None,
                 by_type: dict[str, list[CandidateDoc]] | None = None):
        self.by_item = by_item or {}
        self.by_type = by_type or {}

    def recall(self, item: ChecklistItem) -> list[CandidateDoc]:
        if item.item_id in self.by_item:
            return list(self.by_item[item.item_id])
        return list(self.by_type.get(item.std_type, []))


class NullCandidateProvider:
    """未接入检索时的默认实现：不返回任何候选，全部项落 missing，不产生假阳性。"""

    def recall(self, item: ChecklistItem) -> list[CandidateDoc]:
        return []


class RejectAllDecideProvider:
    """未接入 decide 时的默认实现：p 恒为 0，最高只能落 missing/pending，绝不自动 matched。"""

    def decide(self, item: ChecklistItem, candidate: CandidateDoc) -> DecideResult:
        return DecideResult(p=0.0, reason="decide 未接入")
