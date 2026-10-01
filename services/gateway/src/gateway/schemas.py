"""网关对外数据结构，字段与 contracts/gateway.md 保持一致。"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class Purpose(str, Enum):
    """调用意图。判定类走 decide（小模型），避免用大模型烧 token。"""

    generate = "generate"
    decide = "decide"
    embed = "embed"
    rerank = "rerank"


class Sensitivity(str, Enum):
    """数据密级，L1 最低、L4 最高。"""

    L1 = "L1"
    L2 = "L2"
    L3 = "L3"
    L4 = "L4"


class Channel(str, Enum):
    """模型通道。密级决定允许哪些通道。"""

    public_api = "public_api"  # 通用公有云 API
    enterprise_api = "enterprise_api"  # 企业版 API（已签 DPA、不用于训练）
    vpc_self_hosted = "vpc_self_hosted"  # VPC 内自部署


class Principal(BaseModel):
    """调用主体，用于按部门出账与审计。"""

    user_id: str
    dept: list[str] = Field(default_factory=list)


class InvokeRequest(BaseModel):
    """统一调用入口的请求体。preferred_model 只是建议，不是承诺。"""

    purpose: Purpose
    sensitivity: Sensitivity
    preferred_model: str | None = None
    principal: Principal
    payload: dict[str, Any] = Field(default_factory=dict)


class Usage(BaseModel):
    """单次调用的用量与成本，成本单位为人民币元。"""

    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost_cny: float = 0.0


class InvokeResponse(BaseModel):
    """统一返回体。

    发生任何通道/模型切换时 `switched_reason` 必须非空——静默切换视为缺陷。
    """

    model: str
    channel: Channel
    switched_reason: str | None = None
    output: dict[str, Any] = Field(default_factory=dict)
    usage: Usage
    budget_alert: str | None = None
