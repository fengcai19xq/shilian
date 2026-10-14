"""评测工具：指标计算与 CLI。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from decide.deciders import DecideResult
from decide.eval import (
    Sample,
    SampleOutcome,
    SimulatedGateway,
    compute_metrics,
    estimate_cost,
    load_dataset,
    main,
    run,
)

DATASET = Path(__file__).parent / "data" / "eval_set.jsonl"


def _outcome(need: bool, pred: bool, cat: str, route_ok: bool, cost: float, ms: float):
    expected = "kb_empty" if need else "general"
    return SampleOutcome(
        sample=Sample("x", "q", cat, need, expected),
        probability=0.9 if pred else 0.1,
        predicted_internal=pred,
        route_decision=expected if route_ok else "enterprise",
        backend="jev",
        fallback=False,
        cost_cny=cost,
        latency_ms=ms,
    )


def test_compute_metrics_known_values():
    outcomes = [
        _outcome(True, True, "kb_miss", True, 0.01, 10),  # tp
        _outcome(True, True, "kb_miss", True, 0.01, 20),  # tp
        _outcome(True, False, "kb_miss", False, 0.01, 30),  # fn
        _outcome(False, True, "general", False, 0.03, 40),  # fp
        _outcome(False, False, "general", True, 0.04, 100),  # tn
    ]
    r = compute_metrics("jev", outcomes)
    assert r.confusion == {"tp": 2, "fp": 1, "tn": 1, "fn": 1}
    assert r.accuracy == 0.6
    assert r.precision == pytest.approx(0.6667, abs=1e-4)
    assert r.recall == pytest.approx(0.6667, abs=1e-4)
    assert r.f1 == pytest.approx(0.6667, abs=1e-4)
    assert r.route_accuracy == 0.6
    assert r.category_accuracy == {"general": 0.5, "kb_miss": pytest.approx(0.6667, abs=1e-4)}
    assert r.avg_cost_cny == pytest.approx(0.02)
    assert r.total_cost_cny == pytest.approx(0.1)
    assert r.avg_latency_ms == 40.0
    assert r.p95_latency_ms == 100.0


def test_compute_metrics_no_positive_predictions():
    r = compute_metrics("rule", [_outcome(True, False, "kb_miss", False, 0, 1)])
    assert r.precision == 0.0 and r.recall == 0.0 and r.f1 == 0.0


def test_estimate_cost_prefers_usage_cost_then_tokens():
    with_cost = DecideResult(0.5, "jev", usage={"cost_cny": 0.12, "prompt_tokens": 9})
    assert estimate_cost(with_cost) == 0.12
    tokens_only = DecideResult(
        0.5, "jev", model="m", usage={"prompt_tokens": 1500, "completion_tokens": 500}
    )
    assert estimate_cost(tokens_only, {"m": 0.01}) == pytest.approx(0.02)
    assert estimate_cost(DecideResult(0.5, "rule")) == 0.0


def test_dataset_has_three_categories_and_30_plus_samples():
    samples = load_dataset(DATASET)
    assert len(samples) >= 30
    cats = {s.category for s in samples}
    assert cats == {"enterprise", "general", "kb_miss"}
    assert len({s.id for s in samples}) == len(samples)
    for s in samples:
        assert s.need_internal == (s.category != "general")


def test_run_all_backends_with_simulated_gateway():
    reports = run(DATASET, ["rule", "jev", "llm"], SimulatedGateway())
    by_name = {r.backend: r for r in reports}
    assert set(by_name) == {"rule", "jev", "llm"}
    assert by_name["rule"].avg_cost_cny == 0.0
    assert by_name["jev"].avg_cost_cny > 0
    assert by_name["llm"].avg_cost_cny > by_name["jev"].avg_cost_cny
    for r in reports:
        assert r.n >= 30 and 0 <= r.accuracy <= 1 and r.fallback_count == 0


def test_cli_json_output(tmp_path, capsys):
    out = tmp_path / "report.json"
    code = main(
        [
            "--dataset",
            str(DATASET),
            "--backends",
            "rule,jev,llm",
            "--format",
            "json",
            "--output",
            str(out),
        ]
    )
    assert code == 0
    printed = json.loads(capsys.readouterr().out)
    assert [r["backend"] for r in printed["reports"]] == ["rule", "jev", "llm"]
    for key in ["accuracy", "precision", "recall", "avg_cost_cny", "avg_latency_ms"]:
        assert key in printed["reports"][0]
    assert json.loads(out.read_text("utf-8")) == printed


def test_cli_table_and_bad_backend(capsys):
    assert main(["--dataset", str(DATASET), "--backends", "rule"]) == 0
    assert "accuracy" not in capsys.readouterr().err
    with pytest.raises(SystemExit):
        main(["--dataset", str(DATASET), "--backends", "gpt"])


def test_load_dataset_reports_bad_line(tmp_path):
    bad = tmp_path / "bad.jsonl"
    bad.write_text('{"id": "1"}\n', "utf-8")
    with pytest.raises(ValueError, match="bad.jsonl:1"):
        load_dataset(bad)
