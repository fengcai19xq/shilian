"""decide() 判定：这个问题是否需要公司内部资料才能回答。

三种实现：
- RuleDecider：纯规则，默认兜底，零成本、无外部依赖
- JevDecider：Jev 判定客户端桩，经 gateway 调用（purpose=decide）
- LlmDecider：小模型判定桩，同样经 gateway 调用

判定类任务不走大模型（见 AGENTS.md「省 token」）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Protocol

from decide.clients import GatewayClient
from decide.models import Principal

# 明显需要公司内部资料的问法
_INTERNAL_HINTS: tuple[str, ...] = (
    "我们公司",
    "本公司",
    "咱们",
    "内部",
    "制度",
    "流程",
    "规定",
    "报销",
    "审批",
    "合同",
    "报价",
    "客户",
    "项目",
    "供应商",
    "考勤",
    "工资",
    "年报",
    "审计",
    "产能",
    "工艺",
)

# 明显的通用知识问法
_GENERAL_HINTS: tuple[str, ...] = (
    "是什么",
    "怎么读",
    "翻译",
    "python",
    "写一首",
    "笑话",
    "菜谱",
    "天气",
    "历史上",
    "定义",
)


@dataclass(frozen=True)
class DecideResult:
    """判定结果。probability 为「需要公司内部资料」的概率。"""

    need_internal_probability: float
    backend: str
    fallback_reason: str | None = None


class Decider(Protocol):
    name: str

    def decide(self, question: str, principal: Principal) -> DecideResult: ...


class RuleDecider:
    """纯规则实现：关键词计分，永远可用，作为其它实现失败时的兜底。"""

    name = "rule"

    def decide(self, question: str, principal: Principal) -> DecideResult:
        text = question.lower()
        internal = sum(1 for w in _INTERNAL_HINTS if w.lower() in text)
        general = sum(1 for w in _GENERAL_HINTS if w.lower() in text)
        if internal == 0 and general == 0:
            probability = 0.3
        else:
            # 归一化到 (0, 1)，内部信号越多概率越高
            probability = (internal + 0.0) / (internal + general + 1.0)
            if internal:
                probability = max(probability, 0.5 + 0.1 * min(internal, 4))
            probability = min(probability, 0.95)
        return DecideResult(need_internal_probability=round(probability, 4), backend=self.name)


class _GatewayDecider:
    """经 gateway 调用判定模型的通用实现；失败时由调用方降级到纯规则。"""

    name = "gateway"
    purpose = "decide"
    preferred_model: str | None = None

    def __init__(self, gateway: GatewayClient, sensitivity: str = "L2") -> None:
        self._gateway = gateway
        self._sensitivity = sensitivity

    def decide(self, question: str, principal: Principal) -> DecideResult:
        result = self._gateway.invoke(
            purpose=self.purpose,
            sensitivity=self._sensitivity,
            payload={
                "task": "need_internal_knowledge",
                "question": question,
            },
            principal=principal,
            preferred_model=self.preferred_model,
        )
        payload = result.get("payload", result)
        probability = payload.get("need_internal_probability")
        if probability is None:
            # 桩实现允许只返回 label
            label = str(payload.get("label", "")).lower()
            probability = 1.0 if label in {"internal", "enterprise", "yes"} else 0.0
        return DecideResult(need_internal_probability=float(probability), backend=self.name)


class JevDecider(_GatewayDecider):
    """Jev 判定客户端桩。"""

    name = "jev"
    preferred_model = "jev-decide"


class LlmDecider(_GatewayDecider):
    """小模型判定桩。"""

    name = "llm"
    preferred_model = "qwen2.5-7b-instruct"


class FallbackDecider:
    """包装任意 decider，异常时自动降级到纯规则并记录原因。"""

    def __init__(self, primary: Decider, fallback: Decider | None = None) -> None:
        self._primary = primary
        self._fallback = fallback or RuleDecider()

    @property
    def name(self) -> str:
        return self._primary.name

    def decide(self, question: str, principal: Principal) -> DecideResult:
        try:
            return self._primary.decide(question, principal)
        except Exception as exc:  # noqa: BLE001 判定失败不阻断路由，兜底到纯规则
            result = self._fallback.decide(question, principal)
            reason = f"{self._primary.name} 判定失败，已降级到纯规则：{type(exc).__name__}"
            return DecideResult(
                need_internal_probability=result.need_internal_probability,
                backend=self._fallback.name,
                fallback_reason=reason,
            )


def build_decider(backend: str, gateway: GatewayClient | None) -> Decider:
    """按配置构造判定实现，未知取值一律回落到纯规则。"""
    normalized = (backend or "rule").strip().lower()
    if normalized in {"jev", "llm"} and gateway is not None:
        primary = JevDecider(gateway) if normalized == "jev" else LlmDecider(gateway)
        return FallbackDecider(primary)
    return RuleDecider()


_WHITESPACE = re.compile(r"\s+")


def normalize_question(question: str) -> str:
    return _WHITESPACE.sub(" ", question).strip()
