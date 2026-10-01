"""测试夹具：统一用 fake 客户端，不发任何真实网络请求、不使用真实密钥。"""

from __future__ import annotations

import pytest

from gateway.clients.fake import FakeClientFactory
from gateway.config import load_masking_config, load_routing_config
from gateway.schemas import InvokeRequest, Principal, Purpose, Sensitivity
from gateway.service import GatewayService


@pytest.fixture
def routing_config():
    return load_routing_config()


@pytest.fixture
def masking_config():
    return load_masking_config()


@pytest.fixture
def factory() -> FakeClientFactory:
    return FakeClientFactory()


@pytest.fixture
def service(routing_config, masking_config, factory) -> GatewayService:
    return GatewayService(
        routing_config=routing_config,
        masking_config=masking_config,
        client_factory=factory,
    )


def make_request(
    purpose: Purpose = Purpose.generate,
    sensitivity: Sensitivity = Sensitivity.L1,
    preferred_model: str | None = None,
    dept: str = "融资部",
    payload: dict | None = None,
) -> InvokeRequest:
    return InvokeRequest(
        purpose=purpose,
        sensitivity=sensitivity,
        preferred_model=preferred_model,
        principal=Principal(user_id="u_10231", dept=[dept]),
        payload=payload if payload is not None else {"prompt": "介绍一下储能系统"},
    )
