"""decide() 多后端 A/B 离线评测。

用法：
    python -m decide.eval --dataset tests/data/eval_set.jsonl --backends rule,jev,llm

数据集为 JSONL，每行：
    {"id", "question", "category": enterprise|general|kb_miss,
     "need_internal": bool, "expected_decision": enterprise|general|kb_empty,
     "probe_top_score": float|null}

输出每个后端的判定指标（以「需要内部资料」为正类的 accuracy/precision/recall/f1）、
按类别准确率、整条路由的准确率、平均成本（取 gateway 返回 usage.cost_cny，缺省时按 token 估算）
与延迟。默认用离线 SimulatedGateway（不联网、不需要密钥），只用于验证评测链路，数值不代表真实模型；
接真实 gateway 用 `--gateway http --gateway-url ...`。
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from decide.clients import GatewayClient, HttpGatewayClient, ProbeResult
from decide.config import Settings
from decide.deciders import Decider, DecideResult, build_decider
from decide.models import Principal, RouteRequest, UserChoice
from decide.routing import RoutingService

# 每千 token 单价（元），仅在 gateway 返回 usage 缺 cost_cny 时用于估算
DEFAULT_PRICE_PER_1K_TOKENS: dict[str, float] = {
    "jev-decide": 0.0005,
    "qwen2.5-7b-instruct": 0.002,
}

# 评测用脱敏主体，不含真实人员信息
EVAL_PRINCIPAL = Principal(user_id="u_eval_000", dept=["评测部"], max_sensitivity="L2")


@dataclass(frozen=True)
class Sample:
    id: str
    question: str
    category: str
    need_internal: bool
    expected_decision: str
    probe_top_score: float | None = None


@dataclass
class SampleOutcome:
    sample: Sample
    probability: float
    predicted_internal: bool
    route_decision: str
    backend: str
    fallback: bool
    cost_cny: float
    latency_ms: float


@dataclass
class BackendReport:
    backend: str
    n: int
    accuracy: float
    precision: float
    recall: float
    f1: float
    route_accuracy: float
    category_accuracy: dict[str, float]
    avg_cost_cny: float
    total_cost_cny: float
    avg_latency_ms: float
    p95_latency_ms: float
    fallback_count: int
    confusion: dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return dict(self.__dict__)


def load_dataset(path: str | Path) -> list[Sample]:
    samples: list[Sample] = []
    with open(path, encoding="utf-8") as fp:
        for lineno, line in enumerate(fp, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                row = json.loads(line)
                samples.append(
                    Sample(
                        id=str(row["id"]),
                        question=str(row["question"]),
                        category=str(row["category"]),
                        need_internal=bool(row["need_internal"]),
                        expected_decision=str(row["expected_decision"]),
                        probe_top_score=row.get("probe_top_score"),
                    )
                )
            except (KeyError, ValueError, TypeError) as exc:
                raise ValueError(f"{path}:{lineno} 数据格式错误：{exc}") from exc
    return samples


def estimate_cost(result: DecideResult, price_per_1k: dict[str, float] | None = None) -> float:
    """优先取 usage.cost_cny；缺失时按 (prompt+completion)/1000 × 单价估算；rule 为 0。"""
    usage = result.usage or {}
    if usage.get("cost_cny") is not None:
        return float(usage["cost_cny"])
    tokens = int(usage.get("prompt_tokens") or 0) + int(usage.get("completion_tokens") or 0)
    if not tokens:
        return 0.0
    prices = price_per_1k if price_per_1k is not None else DEFAULT_PRICE_PER_1K_TOKENS
    return tokens / 1000.0 * prices.get(result.model or "", 0.0)


def _ratio(num: float, den: float) -> float:
    return round(num / den, 4) if den else 0.0


def _p95(values: Sequence[float]) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(0.95 * len(ordered)) - 1))
    return round(ordered[index], 3)


def compute_metrics(backend: str, outcomes: Sequence[SampleOutcome]) -> BackendReport:
    """以「需要内部资料」为正类计算二分类指标，并汇总路由准确率、成本与延迟。"""
    tp = sum(1 for o in outcomes if o.sample.need_internal and o.predicted_internal)
    fp = sum(1 for o in outcomes if not o.sample.need_internal and o.predicted_internal)
    tn = sum(1 for o in outcomes if not o.sample.need_internal and not o.predicted_internal)
    fn = sum(1 for o in outcomes if o.sample.need_internal and not o.predicted_internal)
    n = len(outcomes)
    precision = _ratio(tp, tp + fp)
    recall = _ratio(tp, tp + fn)
    f1 = round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0
    categories: dict[str, list[bool]] = {}
    for o in outcomes:
        categories.setdefault(o.sample.category, []).append(
            o.predicted_internal == o.sample.need_internal
        )
    costs = [o.cost_cny for o in outcomes]
    latencies = [o.latency_ms for o in outcomes]
    return BackendReport(
        backend=backend,
        n=n,
        accuracy=_ratio(tp + tn, n),
        precision=precision,
        recall=recall,
        f1=f1,
        route_accuracy=_ratio(
            sum(1 for o in outcomes if o.route_decision == o.sample.expected_decision), n
        ),
        category_accuracy={k: _ratio(sum(v), len(v)) for k, v in sorted(categories.items())},
        avg_cost_cny=round(statistics.fmean(costs), 6) if costs else 0.0,
        total_cost_cny=round(sum(costs), 6),
        avg_latency_ms=round(statistics.fmean(latencies), 3) if latencies else 0.0,
        p95_latency_ms=_p95(latencies),
        fallback_count=sum(1 for o in outcomes if o.fallback),
        confusion={"tp": tp, "fp": fp, "tn": tn, "fn": fn},
    )


class _SampleRetrieval:
    """按样本给定的 probe_top_score 模拟探测检索。"""

    def __init__(self) -> None:
        self.current: Sample | None = None

    def probe(self, query, principal, kb_scope, top_k, score_threshold) -> ProbeResult:
        score = self.current.probe_top_score if self.current else None
        return ProbeResult(top_score=score, total=0 if score is None else 1)


class _Precomputed:
    """把已算好的判定结果交给路由，避免重复调用 gateway 产生双倍成本。"""

    name = "precomputed"

    def __init__(self) -> None:
        self.result: DecideResult | None = None

    def decide(self, question: str, principal: Principal) -> DecideResult:
        assert self.result is not None
        return self.result


def evaluate_backend(
    backend: str,
    decider: Decider,
    samples: Iterable[Sample],
    settings: Settings,
    price_per_1k: dict[str, float] | None = None,
) -> BackendReport:
    retrieval = _SampleRetrieval()
    precomputed = _Precomputed()
    router = RoutingService(settings=settings, retrieval=retrieval, decider=precomputed)
    threshold = settings.need_internal_probability_threshold
    outcomes: list[SampleOutcome] = []
    for sample in samples:
        result = decider.decide(sample.question, EVAL_PRINCIPAL)
        retrieval.current, precomputed.result = sample, result
        route = router.route(
            RouteRequest(
                question=sample.question,
                principal=EVAL_PRINCIPAL,
                user_choice=UserChoice.AUTO,
                kb_scope=["kb_eval"],
            )
        )
        outcomes.append(
            SampleOutcome(
                sample=sample,
                probability=result.need_internal_probability,
                predicted_internal=result.need_internal_probability >= threshold,
                route_decision=route.decision.value,
                backend=result.backend,
                fallback=result.fallback_reason is not None,
                cost_cny=estimate_cost(result, price_per_1k),
                latency_ms=result.latency_ms or 0.0,
            )
        )
    return compute_metrics(backend, outcomes)


class SimulatedGateway:
    """离线模拟 gateway（purpose=decide），不联网、无密钥，结果确定可复现。

    用扩展关键词表模拟 Jev/小模型的判定能力，并按问题长度构造 usage，仅用于跑通评测链路。
    """

    _INTERNAL = (
        "我们",
        "本公司",
        "咱们",
        "公司",
        "我司",
        "内部",
        "部门",
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
        "年假",
        "加班",
        "年报",
        "审计",
        "产能",
        "工艺",
        "里程碑",
        "额度",
        "试用期",
        "整改",
        "验收",
        "工厂",
        "投诉",
        "出厂",
        "月结",
        "用章",
    )
    _GENERAL = (
        "是什么",
        "什么是",
        "翻译",
        "python",
        "excel",
        "写一首",
        "笑话",
        "菜谱",
        "天气",
        "历史上",
        "定义",
        "原理",
        "区别",
        "怎么做",
    )

    def invoke(
        self,
        purpose: str,
        sensitivity: str,
        payload: dict[str, Any],
        principal: Principal,
        preferred_model: str | None = None,
    ) -> dict[str, Any]:
        question = str(payload.get("question", "")).lower()
        internal = sum(1 for w in self._INTERNAL if w in question)
        general = sum(1 for w in self._GENERAL if w in question)
        model = preferred_model or "jev-decide"
        # 小模型对通用信号更敏感，Jev 对内部信号更敏感，模拟两者的差异
        bias = 0.15 if model == "jev-decide" else 0.05
        probability = min(0.97, max(0.03, 0.35 + bias * internal - 0.2 * general))
        prompt_tokens = 60 + 2 * len(question)
        completion_tokens = 8 if model == "jev-decide" else 24
        cost = (
            (prompt_tokens + completion_tokens)
            / 1000.0
            * DEFAULT_PRICE_PER_1K_TOKENS.get(model, 0.0)
        )
        return {
            "model": model,
            "switched_reason": None,
            "payload": {
                "need_internal_probability": round(probability, 4),
                "reason": f"模拟判定：内部信号 {internal}，通用信号 {general}",
            },
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "cost_cny": round(cost, 6),
            },
        }


def run(
    dataset: str | Path,
    backends: Sequence[str],
    gateway: GatewayClient | None,
    settings: Settings | None = None,
) -> list[BackendReport]:
    settings = settings or Settings()
    samples = load_dataset(dataset)
    reports = []
    for backend in backends:
        decider = build_decider(
            backend,
            gateway,
            timeout_seconds=settings.decider_timeout_seconds,
            sensitivity=settings.decider_sensitivity,
            fallback=settings.decider_fallback,
        )
        reports.append(evaluate_backend(backend, decider, samples, settings))
    return reports


def format_table(reports: Sequence[BackendReport]) -> str:
    header = (
        f"{'backend':<8}{'n':>4}{'acc':>8}{'prec':>8}{'recall':>8}{'f1':>8}"
        f"{'route_acc':>11}{'avg_cost¥':>12}{'avg_ms':>10}{'p95_ms':>10}{'fallback':>10}"
    )
    lines = [header, "-" * len(header)]
    for r in reports:
        lines.append(
            f"{r.backend:<8}{r.n:>4}{r.accuracy:>8.3f}{r.precision:>8.3f}{r.recall:>8.3f}"
            f"{r.f1:>8.3f}{r.route_accuracy:>11.3f}{r.avg_cost_cny:>12.6f}"
            f"{r.avg_latency_ms:>10.3f}{r.p95_latency_ms:>10.3f}{r.fallback_count:>10}"
        )
    lines.append("")
    for r in reports:
        cats = "  ".join(f"{k}={v:.3f}" for k, v in r.category_accuracy.items())
        lines.append(f"{r.backend} 分类准确率：{cats}")
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m decide.eval", description=__doc__.split("\n")[0]
    )
    parser.add_argument("--dataset", required=True, help="JSONL 评测集路径")
    parser.add_argument("--backends", default="rule,jev,llm", help="逗号分隔：rule,jev,llm")
    parser.add_argument(
        "--gateway",
        choices=("fake", "http"),
        default="fake",
        help="fake=离线模拟（默认）；http=真实 gateway",
    )
    parser.add_argument("--gateway-url", default=None, help="http 模式下 gateway 地址，默认读配置")
    parser.add_argument("--format", choices=("table", "json"), default="table")
    parser.add_argument("--output", default=None, help="把 JSON 结果另存到文件")
    args = parser.parse_args(argv)

    backends = [b.strip().lower() for b in args.backends.split(",") if b.strip()]
    unknown = [b for b in backends if b not in {"rule", "jev", "llm"}]
    if unknown:
        parser.error(f"未知后端：{','.join(unknown)}")
    settings = Settings.from_env()
    if args.gateway == "http":
        gateway: GatewayClient = HttpGatewayClient(
            args.gateway_url or settings.gateway_base_url, settings.http_timeout_seconds
        )
    else:
        gateway = SimulatedGateway()

    reports = run(args.dataset, backends, gateway, settings)
    data = {
        "dataset": str(args.dataset),
        "gateway": args.gateway,
        "reports": [r.to_dict() for r in reports],
    }
    if args.output:
        Path(args.output).write_text(json.dumps(data, ensure_ascii=False, indent=2), "utf-8")
    if args.format == "json":
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(format_table(reports))
    return 0


if __name__ == "__main__":
    sys.exit(main())
