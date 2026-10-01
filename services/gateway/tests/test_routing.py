"""密级路由与「切换必带原因」的测试。"""

from __future__ import annotations

import pytest

from gateway.router import NoEligibleModelError, SensitivityRouter
from gateway.schemas import Channel, Purpose, Sensitivity

from .conftest import make_request


def test_l4_绝不路由到外部通道(service, factory):
    """红线：L4 只能落在 VPC 自部署通道。"""
    for preferred in (None, "deepseek-v3", "deepseek-v3-enterprise", "不存在的模型"):
        response = service.invoke(make_request(sensitivity=Sensitivity.L4, preferred_model=preferred))
        assert response.channel is Channel.vpc_self_hosted
        assert service.routing_config.models[response.model].channel is Channel.vpc_self_hosted

    assert factory.calls, "应当真的调用了模型客户端"
    for call in factory.calls:
        assert call.endpoint.endswith("llm.vpc.internal/v1"), "L4 请求不得发往 VPC 之外的地址"


def test_l4_首选外部模型时必须带切换原因(service):
    response = service.invoke(make_request(sensitivity=Sensitivity.L4, preferred_model="deepseek-v3"))
    assert response.switched_reason, "静默切换视为缺陷"
    assert "deepseek-v3" in response.switched_reason
    assert response.model == "qwen2.5-vpc"


def test_l3_不允许公有云通道并带原因(service):
    response = service.invoke(make_request(sensitivity=Sensitivity.L3, preferred_model="deepseek-v3"))
    assert response.channel is Channel.enterprise_api
    assert response.switched_reason and "L3" in response.switched_reason


def test_首选模型合规时不切换也无原因(service):
    response = service.invoke(
        make_request(sensitivity=Sensitivity.L2, preferred_model="deepseek-v3-enterprise")
    )
    assert response.model == "deepseek-v3-enterprise"
    assert response.switched_reason is None


def test_l1_可用公有云且未指定时走默认模型(service):
    response = service.invoke(make_request(sensitivity=Sensitivity.L1))
    assert response.model == "deepseek-v3-enterprise"
    assert response.switched_reason is None


def test_首选模型不支持该用途时降级并说明(service):
    response = service.invoke(
        make_request(purpose=Purpose.embed, sensitivity=Sensitivity.L2, preferred_model="deepseek-v3")
    )
    assert response.model == "bge-m3-enterprise"
    assert response.switched_reason and "不支持" in response.switched_reason


def test_首选模型不在目录时降级并说明(service):
    response = service.invoke(make_request(preferred_model="gpt-不存在"))
    assert response.switched_reason and "不在模型目录" in response.switched_reason


def test_任何切换都必须给出原因(service):
    """遍历各密级与各首选模型：模型名变了就必须有 switched_reason。"""
    for sensitivity in Sensitivity:
        for preferred in service.routing_config.models:
            response = service.invoke(make_request(sensitivity=sensitivity, preferred_model=preferred))
            if response.model != preferred:
                assert response.switched_reason, f"{sensitivity}/{preferred} 发生了静默切换"
            else:
                assert response.switched_reason is None


def test_密级下无可用模型时报错而不是降密级(routing_config):
    stripped = routing_config.__class__(
        policies=routing_config.policies,
        models={k: v for k, v in routing_config.models.items() if v.channel.value != "vpc_self_hosted"},
        default_models=routing_config.default_models,
        low_cost_models=routing_config.low_cost_models,
        budget=routing_config.budget,
    )
    router = SensitivityRouter(stripped)
    with pytest.raises(NoEligibleModelError):
        router.route(purpose=Purpose.generate, sensitivity=Sensitivity.L4)
