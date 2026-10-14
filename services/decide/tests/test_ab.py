"""灰度：分桶稳定性、影子模式不改变响应、配置解析。"""

from __future__ import annotations

import logging
import time
from collections import Counter

import pytest
from fakes import FakeGateway, FakeRetrieval
from fastapi.testclient import TestClient

from decide.ab import (
    SHADOW_BUCKET,
    AbDecider,
    ShadowComparison,
    bucket_for,
    build_decider_from_settings,
)
from decide.app import build_service, create_app
from decide.config import AbConfig, Settings, parse_traffic_split
from decide.deciders import FallbackDecider, RuleDecider
from decide.models import Principal

SPLIT = (("rule", 0.8), ("jev", 0.1), ("llm", 0.1))


def _principal(user_id: str) -> Principal:
    return Principal(user_id=user_id, dept=["测试部"], max_sensitivity="L2")


# ---------- 分桶 ----------


def test_bucket_is_stable_for_same_user():
    buckets = {bucket_for("u_test_042", SPLIT, "salt") for _ in range(100)}
    assert len(buckets) == 1


def test_bucket_is_stable_across_decider_instances():
    settings = Settings(ab=AbConfig(traffic_split=SPLIT, salt="s1"))
    users = [f"u_test_{i:04d}" for i in range(200)]
    first = build_decider_from_settings(settings, FakeGateway())
    second = build_decider_from_settings(settings, FakeGateway())
    assert [first.bucket(_principal(u)) for u in users] == [
        second.bucket(_principal(u)) for u in users
    ]


def test_bucket_distribution_follows_split():
    counts = Counter(bucket_for(f"u_{i}", SPLIT, "salt") for i in range(20000))
    assert abs(counts["rule"] / 20000 - 0.8) < 0.02
    assert abs(counts["jev"] / 20000 - 0.1) < 0.02
    assert abs(counts["llm"] / 20000 - 0.1) < 0.02


def test_bucket_changes_with_salt_but_zero_weight_never_hit():
    users = [f"u_{i}" for i in range(500)]
    a = [bucket_for(u, (("rule", 0.5), ("jev", 0.5)), "a") for u in users]
    b = [bucket_for(u, (("rule", 0.5), ("jev", 0.5)), "b") for u in users]
    assert a != b
    split = parse_traffic_split("rule:0,jev:1")
    assert {bucket_for(u, split, "a") for u in users} == {"jev"}


def test_parse_traffic_split_normalizes_and_validates():
    assert parse_traffic_split("rule:80, jev:10, llm:10") == SPLIT
    assert parse_traffic_split("") == ()
    for bad in ["gpt:10", "rule", "rule:-1", "rule:0"]:
        with pytest.raises(ValueError):
            parse_traffic_split(bad)


def test_ab_settings_from_env(monkeypatch):
    monkeypatch.setenv("DECIDE_AB_TRAFFIC_SPLIT", "rule:2,jev:2")
    monkeypatch.setenv("DECIDE_AB_SHADOW", "jev")
    monkeypatch.setenv("DECIDE_AB_SHADOW_ASYNC", "false")
    monkeypatch.setenv("DECIDE_DECIDER_TIMEOUT", "0.8")
    s = Settings.from_env()
    assert s.ab.traffic_split == (("rule", 0.5), ("jev", 0.5))
    assert s.ab.shadow == ("jev",)
    assert s.ab.shadow_async is False
    assert s.decider_timeout_seconds == 0.8


def test_ab_disabled_is_backward_compatible():
    decider = build_decider_from_settings(Settings(decider_backend="jev"), FakeGateway())
    assert isinstance(decider, FallbackDecider)
    assert isinstance(build_decider_from_settings(Settings(), FakeGateway()), RuleDecider)


def test_ab_decider_uses_bucket_backend(principal):
    gateway = FakeGateway({"payload": {"need_internal_probability": 0.9}})
    settings = Settings(ab=AbConfig(traffic_split=(("jev", 1.0),)))
    result = build_decider_from_settings(settings, gateway).decide("随便问问", principal)
    assert result.backend == "jev"
    assert result.ab_bucket == "jev"
    assert gateway.calls[0]["preferred_model"] == "jev-decide"


def test_route_logs_ab_bucket_and_keeps_contract_fields(caplog):
    gateway = FakeGateway({"payload": {"need_internal_probability": 0.1}})
    settings = Settings(ab=AbConfig(traffic_split=(("jev", 1.0),)))
    client = TestClient(create_app(build_service(settings, FakeRetrieval(top_score=None), gateway)))
    with caplog.at_level(logging.INFO, logger="decide.routing"):
        body = client.post(
            "/decide/route",
            json={"question": "随便问问", "principal": {"user_id": "u_t", "dept": ["测试部"]}},
        ).json()
    assert body["evidence"]["decider_backend"] == "jev"
    assert "ab_bucket" not in body["evidence"]  # 契约未定义，只写日志
    assert any('"ab_bucket": "jev"' in r.getMessage() for r in caplog.records)
    assert all("随便问问" not in r.getMessage() for r in caplog.records)  # 不落问题原文


# ---------- 影子模式 ----------


def _route_body(settings: Settings, gateway: FakeGateway) -> dict:
    client = TestClient(create_app(build_service(settings, FakeRetrieval(top_score=0.1), gateway)))
    return client.post(
        "/decide/route",
        json={
            "question": "我们公司的合同审批流程",
            "principal": {"user_id": "u_t", "dept": ["测试部"]},
        },
    ).json()


@pytest.mark.parametrize(
    "gateway",
    [
        FakeGateway({"payload": {"need_internal_probability": 0.0}}),  # 与 rule 结论相反
        FakeGateway(error=RuntimeError("gateway down")),
        FakeGateway({"payload": {}}),
    ],
)
def test_shadow_does_not_change_response(gateway):
    baseline = _route_body(Settings(), FakeGateway())
    shadow_settings = Settings(ab=AbConfig(shadow=("jev",), shadow_async=False))
    assert _route_body(shadow_settings, gateway) == baseline
    assert len(gateway.calls) == 1  # 影子确实调用了 jev
    assert baseline["evidence"]["decider_backend"] == "rule"


def test_shadow_records_comparison(principal):
    records: list[ShadowComparison] = []
    gateway = FakeGateway(
        {
            "model": "jev-decide",
            "payload": {"need_internal_probability": 0.1},
            "usage": {"prompt_tokens": 10, "completion_tokens": 2, "cost_cny": 0.0001},
        }
    )
    settings = Settings(ab=AbConfig(shadow=("jev",), shadow_async=False))
    decider = build_decider_from_settings(settings, gateway, shadow_sink=records.append)
    result = decider.decide("我们公司的合同审批流程", principal)
    assert result.backend == "rule"
    assert result.ab_bucket == SHADOW_BUCKET
    assert len(records) == 1
    record = records[0]
    assert record.shadow_backend == "jev"
    assert record.shadow_probability == 0.1
    assert record.agree is False
    assert record.shadow_cost_cny == 0.0001
    assert principal.user_id not in record.user_hash


def test_shadow_failure_is_recorded_not_fallen_back(principal):
    records: list[ShadowComparison] = []
    settings = Settings(ab=AbConfig(shadow=("jev",), shadow_async=False))
    decider = build_decider_from_settings(
        settings, FakeGateway(error=RuntimeError("down")), shadow_sink=records.append
    )
    decider.decide("q", principal)
    assert records[0].shadow_probability is None
    assert records[0].shadow_error and "DeciderError" in records[0].shadow_error


def test_shadow_async_does_not_block_response(principal):
    records: list[ShadowComparison] = []
    gateway = FakeGateway({"payload": {"need_internal_probability": 0.9}}, delay=0.3)
    settings = Settings(
        decider_timeout_seconds=5.0, ab=AbConfig(shadow=("jev",), shadow_async=True)
    )
    decider = build_decider_from_settings(settings, gateway, shadow_sink=records.append)
    assert isinstance(decider, AbDecider)
    started = time.perf_counter()
    result = decider.decide("q", principal)
    assert time.perf_counter() - started < 0.2
    assert result.backend == "rule"
    decider.shadow_runner.flush(timeout=5)
    assert len(records) == 1 and records[0].shadow_probability == 0.9


def test_shadow_takes_precedence_over_split(principal):
    gateway = FakeGateway({"payload": {"need_internal_probability": 0.9}})
    settings = Settings(
        ab=AbConfig(traffic_split=(("jev", 1.0),), shadow=("jev",), shadow_async=False)
    )
    result = build_decider_from_settings(settings, gateway, shadow_sink=lambda r: None).decide(
        "你好", principal
    )
    assert result.backend == "rule"
