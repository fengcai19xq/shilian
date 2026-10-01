"""对外数据结构。字段命名与 contracts/retrieval.md、contracts/gateway.md 保持一致。"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field


class Sensitivity(str, Enum):
    L1 = "L1"
    L2 = "L2"
    L3 = "L3"
    L4 = "L4"


class Principal(BaseModel):
    """权限主体，契约见 contracts/retrieval.md。由网关按会话态注入，不由前端伪造。"""

    model_config = ConfigDict(extra="allow")

    user_id: str
    dept: list[str] = Field(default_factory=list)
    roles: list[str] = Field(default_factory=list)
    max_sensitivity: Sensitivity = Sensitivity.L1
    projects: list[str] = Field(default_factory=list)


class UserChoice(str, Enum):
    """用户在界面上的显式选择。auto 表示交给智能路由。"""

    ENTERPRISE = "enterprise"
    GENERAL = "general"
    AUTO = "auto"


class RouteDecision(str, Enum):
    """路由结果。kb_empty 必须如实告知用户，禁止静默转通用模型。"""

    ENTERPRISE = "enterprise"
    GENERAL = "general"
    KB_EMPTY = "kb_empty"


class RouteRequest(BaseModel):
    question: str = Field(min_length=1)
    principal: Principal
    user_choice: UserChoice = UserChoice.AUTO
    kb_scope: list[str] = Field(default_factory=list)


class RouteEvidence(BaseModel):
    """路由依据，供界面展示「检索范围」与调试。

    走通用模型时只保留不含公司信息的字段（命中实体、检索范围一律清空）。
    """

    source: str = Field(description="决策来源：user_choice / entity / probe / decide")
    matched_entities: list[str] = Field(default_factory=list, description="命中的企业实体词")
    probe_top_score: float | None = Field(default=None, description="探测检索最高分")
    probe_total: int | None = Field(default=None, description="探测检索命中数")
    probe_threshold: float | None = Field(default=None, description="判定为企业知识的阈值")
    need_internal_probability: float | None = Field(
        default=None, description="「需要公司内部资料才能回答」的判定概率"
    )
    decider_backend: str | None = Field(default=None, description="实际生效的判定实现")
    decider_fallback_reason: str | None = Field(
        default=None, description="判定实现失败时降级到纯规则的原因"
    )
    scope_desc: str | None = Field(default=None, description="检索范围描述，仅企业知识路径返回")


class RouteResponse(BaseModel):
    decision: RouteDecision
    reason: str = Field(description="面向用户的中文说明")
    evidence: RouteEvidence
    confirm_required: bool = Field(
        default=False, description="为真时前端必须二次确认，不得自动转通用模型"
    )
    allow_general_fallback: bool = Field(
        default=False,
        description="是否允许（在用户显式确认后）改走通用模型；kb_empty 时由用户决定，系统不自动切换",
    )
