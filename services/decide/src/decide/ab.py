"""decide() 灰度：按 user_id 哈希稳定分桶（rule/jev/llm）与影子模式。

- 分桶：sha256(salt:user_id) 映射到 [0, 1)，按 ab.traffic_split 累计占比落桶；同一用户始终同一桶
- 影子模式：主路径固定用 rule 返回，同时调用影子后端（如 jev）只记录对比，不影响响应；
  默认放后台线程执行，影子后端的异常一律吞掉只记日志

ab_bucket 不在 contracts/decide.md 的 evidence 字段里，按约定只写日志，不改契约。
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading
from collections.abc import Callable, Mapping
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import asdict, dataclass, replace

from decide.clients import GatewayClient
from decide.config import AbConfig, Settings
from decide.deciders import Decider, DecideResult, RuleDecider, build_decider
from decide.models import Principal

logger = logging.getLogger("decide.ab")

SHADOW_BUCKET = "shadow"


def bucket_point(user_id: str, salt: str) -> float:
    digest = hashlib.sha256(f"{salt}:{user_id}".encode()).digest()
    return int.from_bytes(digest[:8], "big") / float(1 << 64)


def bucket_for(user_id: str, traffic_split: tuple[tuple[str, float], ...], salt: str) -> str:
    """按 user_id 稳定分桶。traffic_split 为空时返回 rule。"""
    if not traffic_split:
        return "rule"
    point = bucket_point(user_id, salt)
    cumulative = 0.0
    for name, weight in traffic_split:
        cumulative += weight
        if point < cumulative:
            return name
    return traffic_split[-1][0]


def short_hash(text: str) -> str:
    """日志里只记哈希，不落原文（问题可能含公司信息）。"""
    return hashlib.sha256(text.encode()).hexdigest()[:12]


@dataclass(frozen=True)
class ShadowComparison:
    """一次影子对比记录。"""

    user_hash: str
    question_hash: str
    primary_backend: str
    primary_probability: float
    shadow_backend: str
    shadow_probability: float | None
    shadow_error: str | None
    agree: bool | None
    shadow_latency_ms: float | None
    shadow_model: str | None
    shadow_cost_cny: float | None


def log_comparison(record: ShadowComparison) -> None:
    logger.info("decide_shadow %s", json.dumps(asdict(record), ensure_ascii=False))


class ShadowRunner:
    """执行影子调用并把对比结果交给 sink（默认写日志）。"""

    def __init__(
        self,
        deciders: Mapping[str, Decider],
        threshold: float,
        run_async: bool = True,
        sink: Callable[[ShadowComparison], None] | None = None,
        max_workers: int = 4,
    ) -> None:
        self._deciders = dict(deciders)
        self._threshold = threshold
        self._async = run_async
        self._sink = sink or log_comparison
        self._executor = (
            ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="decide-shadow")
            if run_async
            else None
        )
        self._pending: set[Future[None]] = set()
        self._lock = threading.Lock()

    def submit(self, question: str, principal: Principal, primary: DecideResult) -> None:
        for name, decider in self._deciders.items():
            if self._executor is None:
                self._run_one(name, decider, question, principal, primary)
                continue
            future = self._executor.submit(
                self._run_one, name, decider, question, principal, primary
            )
            with self._lock:
                self._pending.add(future)
            future.add_done_callback(self._discard)

    def flush(self, timeout: float | None = None) -> None:
        """等待已提交的影子调用结束（测试与优雅停机用）。"""
        with self._lock:
            pending = list(self._pending)
        for future in pending:
            future.result(timeout=timeout)

    def _discard(self, future: Future[None]) -> None:
        with self._lock:
            self._pending.discard(future)

    def _run_one(
        self,
        name: str,
        decider: Decider,
        question: str,
        principal: Principal,
        primary: DecideResult,
    ) -> None:
        try:
            try:
                shadow = decider.decide(question, principal)
                error = shadow.fallback_reason
            except Exception as exc:  # noqa: BLE001 影子调用失败不影响主路径
                shadow, error = None, f"{type(exc).__name__}: {exc}"
            record = self._compare(name, question, principal, primary, shadow, error)
            self._sink(record)
        except Exception:
            logger.exception("影子对比记录失败")

    def _compare(
        self,
        name: str,
        question: str,
        principal: Principal,
        primary: DecideResult,
        shadow: DecideResult | None,
        error: str | None,
    ) -> ShadowComparison:
        shadow_prob = shadow.need_internal_probability if shadow else None
        agree = None
        if shadow_prob is not None:
            agree = (shadow_prob >= self._threshold) == (
                primary.need_internal_probability >= self._threshold
            )
        cost = None
        if shadow and shadow.usage and shadow.usage.get("cost_cny") is not None:
            cost = float(shadow.usage["cost_cny"])
        return ShadowComparison(
            user_hash=short_hash(principal.user_id),
            question_hash=short_hash(question),
            primary_backend=primary.backend,
            primary_probability=primary.need_internal_probability,
            shadow_backend=name,
            shadow_probability=shadow_prob,
            shadow_error=error,
            agree=agree,
            shadow_latency_ms=shadow.latency_ms if shadow else None,
            shadow_model=shadow.model if shadow else None,
            shadow_cost_cny=cost,
        )


class AbDecider:
    """灰度判定：影子模式优先（主路径 rule），否则按 traffic_split 分桶选后端。"""

    name = "ab"

    def __init__(
        self,
        deciders: Mapping[str, Decider],
        config: AbConfig,
        shadow_runner: ShadowRunner | None = None,
    ) -> None:
        self._deciders = dict(deciders)
        self._deciders.setdefault("rule", RuleDecider())
        self._config = config
        self._shadow = shadow_runner

    @property
    def shadow_runner(self) -> ShadowRunner | None:
        return self._shadow

    def bucket(self, principal: Principal) -> str:
        if self._config.shadow:
            return SHADOW_BUCKET
        return bucket_for(principal.user_id, self._config.traffic_split, self._config.salt)

    def decide(self, question: str, principal: Principal) -> DecideResult:
        bucket = self.bucket(principal)
        backend = "rule" if bucket == SHADOW_BUCKET else bucket
        result = self._deciders[backend].decide(question, principal)
        result = replace(result, ab_bucket=bucket)
        if bucket == SHADOW_BUCKET and self._shadow is not None:
            try:
                self._shadow.submit(question, principal, result)
            except Exception:
                logger.exception("影子调用提交失败")
        return result


def build_decider_from_settings(
    settings: Settings,
    gateway: GatewayClient | None,
    shadow_sink: Callable[[ShadowComparison], None] | None = None,
) -> Decider:
    """按 Settings 构造判定实现：未开灰度时等价于 build_decider(settings.decider_backend)。"""
    common = {
        "timeout_seconds": settings.decider_timeout_seconds,
        "sensitivity": settings.decider_sensitivity,
    }
    ab = settings.ab
    if not ab.enabled:
        return build_decider(
            settings.decider_backend, gateway, fallback=settings.decider_fallback, **common
        )
    names = {"rule", *(name for name, _ in ab.traffic_split)}
    deciders = {
        name: build_decider(name, gateway, fallback=settings.decider_fallback, **common)
        for name in names
    }
    runner = None
    shadow_names = [n for n in ab.shadow if n != "rule"]
    if shadow_names:
        # 影子后端不包降级，失败原样记录，避免把 rule 的结果误记成 jev 的
        shadow_deciders = {
            n: build_decider(n, gateway, fallback="none", **common) for n in shadow_names
        }
        runner = ShadowRunner(
            shadow_deciders,
            threshold=settings.need_internal_probability_threshold,
            run_async=ab.shadow_async,
            sink=shadow_sink,
        )
    return AbDecider(deciders, ab, runner)
