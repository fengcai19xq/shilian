"""用量累计、预算告警与超预算自动降级的测试。"""

from __future__ import annotations

import logging

from gateway.schemas import Purpose, Sensitivity, Usage
from gateway.usage import UsageLedger

from .conftest import make_request


def test_返回体带用量且成本按价目表计算(service, factory):
    factory.client.prompt_tokens = 2841
    factory.client.completion_tokens = 512
    response = service.invoke(
        make_request(sensitivity=Sensitivity.L2, preferred_model="deepseek-v3-enterprise")
    )
    assert response.usage.prompt_tokens == 2841
    assert response.usage.completion_tokens == 512
    # 2.841 * 0.004 + 0.512 * 0.012 = 0.017508
    assert response.usage.cost_cny == 0.017508


def test_用量按部门_用途_模型正确累计(service):
    for _ in range(3):
        service.invoke(make_request(dept="融资部"))
    service.invoke(make_request(dept="融资部", purpose=Purpose.decide))
    service.invoke(make_request(dept="财务部"))

    ledger = service.ledger
    rz = ledger.get("融资部", Purpose.generate, "deepseek-v3-enterprise")
    assert rz.calls == 3
    assert rz.prompt_tokens == 300 and rz.completion_tokens == 150
    assert ledger.get("融资部", Purpose.decide, "qwen-small-enterprise").calls == 1
    assert ledger.get("财务部", Purpose.generate, "deepseek-v3-enterprise").calls == 1
    assert ledger.get("财务部", Purpose.decide, "qwen-small-enterprise").calls == 0

    spec = service.routing_config.models["deepseek-v3-enterprise"]
    small = service.routing_config.models["qwen-small-enterprise"]
    expected = round(3 * spec.cost_cny(100, 50) + small.cost_cny(100, 50), 6)
    assert ledger.dept_total("融资部") == expected
    assert ledger.dept_total("财务部") == spec.cost_cny(100, 50)


def test_多部门主体只记主部门不重复计费(service):
    request = make_request(dept="融资部")
    request.principal.dept.append("财务部")
    service.invoke(request)
    assert service.ledger.dept_total("融资部") > 0
    assert service.ledger.dept_total("财务部") == 0


def test_账本按月隔离():
    ledger = UsageLedger()
    ledger.record("融资部", Purpose.generate, "m", Usage(cost_cny=1.0), period="2026-09")
    ledger.record("融资部", Purpose.generate, "m", Usage(cost_cny=2.0), period="2026-10")
    assert ledger.dept_total("融资部", period="2026-09") == 1.0
    assert ledger.dept_total("融资部", period="2026-10") == 2.0


def _seed_spend(service, dept: str, cost: float) -> None:
    service.ledger.record(dept, Purpose.generate, "deepseek-v3-enterprise", Usage(cost_cny=cost))


def test_达80预算打告警日志但不降级(service, caplog):
    _seed_spend(service, "融资部", 3000.0 * 0.85)
    with caplog.at_level(logging.WARNING, logger="gateway.usage"):
        response = service.invoke(make_request(dept="融资部"))
    assert response.model == "deepseek-v3-enterprise"
    assert response.switched_reason is None
    assert response.budget_alert and "80%" in response.budget_alert
    assert any("融资部" in r.getMessage() and r.levelno == logging.WARNING for r in caplog.records)


def test_未到80不告警(service, caplog):
    _seed_spend(service, "融资部", 100.0)
    with caplog.at_level(logging.WARNING, logger="gateway.usage"):
        response = service.invoke(make_request(dept="融资部"))
    assert response.budget_alert is None
    assert not caplog.records


def test_超预算自动降级到低成本模型并带原因(service, caplog):
    _seed_spend(service, "融资部", 3000.0)
    with caplog.at_level(logging.WARNING, logger="gateway.usage"):
        response = service.invoke(
            make_request(dept="融资部", sensitivity=Sensitivity.L2, preferred_model="deepseek-v3-enterprise")
        )
    assert response.model == "qwen-small-enterprise"
    assert response.switched_reason and "预算" in response.switched_reason
    assert response.budget_alert and "100%" in response.budget_alert
    assert any(r.levelno == logging.ERROR for r in caplog.records)
    # 其它部门不受影响
    other = service.invoke(make_request(dept="财务部"))
    assert other.model == "deepseek-v3-enterprise"


def test_超预算降级不突破密级(service):
    """L4 超预算时只能在 VPC 通道内选低成本模型，不能为省钱出 VPC。"""
    _seed_spend(service, "融资部", 99999.0)
    response = service.invoke(make_request(dept="融资部", sensitivity=Sensitivity.L4))
    assert response.channel.value == "vpc_self_hosted"


def test_刚跨过80的调用当次即告警(service, factory):
    _seed_spend(service, "融资部", 3000.0 * 0.8 - 0.001)
    factory.client.prompt_tokens = 10_000
    response = service.invoke(make_request(dept="融资部"))
    assert response.budget_alert and "80%" in response.budget_alert
