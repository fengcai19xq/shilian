"""decide() 判定：这个问题是否需要公司内部资料才能回答。

三种实现：
- RuleDecider：纯规则，默认兜底，零成本、无外部依赖
- JevDecider：Jev 判定，经 gateway `POST /v1/invoke`（purpose=decide，preferred_model=jev-decide）
- LlmDecider：小模型判定，同样经 gateway 调用

判定类任务不走大模型（见 AGENTS.md「省 token」）。
"""

from __future__ import annotations

import math
import re
import time
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeoutError
from dataclasses import dataclass, replace
from typing import Any, Protocol

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

_POSITIVE_LABELS = frozenset({"internal", "enterprise", "yes", "true"})
_NEGATIVE_LABELS = frozenset({"general", "external", "no", "false"})


@dataclass(frozen=True)
class DecideResult:
    """判定结果。probability 为「需要公司内部资料」的概率。

    backend 为实际生效的实现（降级后为 rule）；usage/model/switched_reason 原样取自 gateway 返回，
    供成本核算与评测使用；ab_bucket 为灰度分桶（未开灰度为 None）。
    """

    need_internal_probability: float
    backend: str
    fallback_reason: str | None = None
    reason: str | None = None
    model: str | None = None
    usage: dict[str, Any] | None = None
    switched_reason: str | None = None
    latency_ms: float | None = None
    ab_bucket: str | None = None


class Decider(Protocol):
    name: str

    def decide(self, question: str, principal: Principal) -> DecideResult: ...


class DeciderError(Exception):
    """判定实现不可用。kind：timeout / gateway_error / invalid_response。"""

    def __init__(self, kind: str, detail: str) -> None:
        super().__init__(detail)
        self.kind = kind
        self.detail = detail


class RuleDecider:
    """纯规则实现：关键词计分，永远可用，作为其它实现失败时的兜底。"""

    name = "rule"

    def decide(self, question: str, principal: Principal) -> DecideResult:
        started = time.perf_counter()
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
        return DecideResult(
            need_internal_probability=round(probability, 4),
            backend=self.name,
            reason=f"规则计分：内部信号 {internal} 个，通用信号 {general} 个",
            latency_ms=_elapsed_ms(started),
        )


def parse_decide_response(result: dict[str, Any]) -> tuple[float, str | None]:
    """解析 gateway purpose=decide 的返回，得到 (need_internal_probability, reason)。

    契约：`payload.need_internal_probability`（0~1），可选 `label`；reason 为可选说明。
    兼容桩实现直接把字段放在顶层的写法。缺字段、非数字、越界一律视为无效返回。
    """
    if not isinstance(result, dict):
        raise DeciderError("invalid_response", "返回体不是 JSON 对象")
    payload = result.get("payload", result)
    if not isinstance(payload, dict):
        raise DeciderError("invalid_response", "payload 不是 JSON 对象")
    reason = payload.get("reason") or result.get("reason")
    reason = str(reason) if reason else None
    raw = payload.get("need_internal_probability")
    if raw is None:
        label = str(payload.get("label", "")).strip().lower()
        if label in _POSITIVE_LABELS:
            return 1.0, reason
        if label in _NEGATIVE_LABELS:
            return 0.0, reason
        raise DeciderError("invalid_response", "缺少 need_internal_probability")
    if isinstance(raw, bool):
        raise DeciderError("invalid_response", "need_internal_probability 不是数字")
    try:
        probability = float(raw)
    except (TypeError, ValueError) as exc:
        raise DeciderError("invalid_response", "need_internal_probability 不是数字") from exc
    if math.isnan(probability) or not 0.0 <= probability <= 1.0:
        raise DeciderError("invalid_response", f"need_internal_probability 越界：{raw}")
    return probability, reason


class _GatewayDecider:
    """经 gateway 调用判定模型的通用实现；失败抛 DeciderError，由 FallbackDecider 降级。"""

    name = "gateway"
    purpose = "decide"
    preferred_model: str | None = None

    def __init__(
        self,
        gateway: GatewayClient,
        sensitivity: str = "L2",
        timeout_seconds: float | None = None,
    ) -> None:
        self._gateway = gateway
        self._sensitivity = sensitivity
        self._timeout = timeout_seconds
        self._executor: ThreadPoolExecutor | None = None

    def build_payload(self, question: str) -> dict[str, Any]:
        return {"task": "need_internal_knowledge", "question": question}

    def decide(self, question: str, principal: Principal) -> DecideResult:
        started = time.perf_counter()
        result = self._invoke(question, principal)
        probability, reason = parse_decide_response(result)
        usage = result.get("usage") if isinstance(result.get("usage"), dict) else None
        return DecideResult(
            need_internal_probability=probability,
            backend=self.name,
            reason=reason,
            model=result.get("model"),
            usage=usage,
            switched_reason=result.get("switched_reason"),
            latency_ms=_elapsed_ms(started),
        )

    def _invoke(self, question: str, principal: Principal) -> dict[str, Any]:
        kwargs = {
            "purpose": self.purpose,
            "sensitivity": self._sensitivity,
            "payload": self.build_payload(question),
            "principal": principal,
            "preferred_model": self.preferred_model,
        }
        try:
            if not self._timeout:
                return self._gateway.invoke(**kwargs)
            if self._executor is None:
                self._executor = ThreadPoolExecutor(
                    max_workers=8, thread_name_prefix=f"decide-{self.name}"
                )
            future = self._executor.submit(self._gateway.invoke, **kwargs)
            try:
                return future.result(timeout=self._timeout)
            except FutureTimeoutError as exc:
                future.cancel()
                raise DeciderError("timeout", f"超过 {self._timeout:g}s 未返回") from exc
        except DeciderError:
            raise
        except TimeoutError as exc:
            raise DeciderError("timeout", type(exc).__name__) from exc
        except Exception as exc:
            if "timeout" in type(exc).__name__.lower():
                raise DeciderError("timeout", type(exc).__name__) from exc
            raise DeciderError("gateway_error", type(exc).__name__) from exc


class JevDecider(_GatewayDecider):
    """Jev 判定：经 gateway 调用 jev-decide。"""

    name = "jev"
    preferred_model = "jev-decide"


class LlmDecider(_GatewayDecider):
    """小模型判定。"""

    name = "llm"
    preferred_model = "qwen2.5-7b-instruct"


_FALLBACK_KIND_DESC = {
    "timeout": "调用超时",
    "gateway_error": "调用 gateway 失败",
    "invalid_response": "返回无效",
}


class FallbackDecider:
    """包装任意 decider，失败/超时/返回无效时降级到纯规则，并在 fallback_reason 写明原因。"""

    def __init__(self, primary: Decider, fallback: Decider | None = None) -> None:
        self._primary = primary
        self._fallback = fallback or RuleDecider()

    @property
    def name(self) -> str:
        return self._primary.name

    @property
    def primary(self) -> Decider:
        return self._primary

    def decide(self, question: str, principal: Principal) -> DecideResult:
        try:
            return self._primary.decide(question, principal)
        except Exception as exc:  # noqa: BLE001 判定失败不阻断路由，兜底到纯规则
            if isinstance(exc, DeciderError):
                desc = f"{_FALLBACK_KIND_DESC.get(exc.kind, exc.kind)}（{exc.detail}）"
            else:
                desc = f"判定失败（{type(exc).__name__}）"
            result = self._fallback.decide(question, principal)
            reason = f"{self._primary.name} {desc}，已降级到纯规则"
            return replace(result, backend=self._fallback.name, fallback_reason=reason)


def build_decider(
    backend: str,
    gateway: GatewayClient | None,
    *,
    timeout_seconds: float | None = None,
    sensitivity: str = "L2",
    fallback: str = "rule",
) -> Decider:
    """按配置构造判定实现，未知取值一律回落到纯规则。fallback=none 时不包降级（仅调试用）。"""
    normalized = (backend or "rule").strip().lower()
    if normalized in {"jev", "llm"} and gateway is not None:
        cls = JevDecider if normalized == "jev" else LlmDecider
        primary = cls(gateway, sensitivity=sensitivity, timeout_seconds=timeout_seconds)
        if (fallback or "rule").strip().lower() == "none":
            return primary
        return FallbackDecider(primary)
    return RuleDecider()


def _elapsed_ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 3)


_WHITESPACE = re.compile(r"\s+")


def normalize_question(question: str) -> str:
    return _WHITESPACE.sub(" ", question).strip()
