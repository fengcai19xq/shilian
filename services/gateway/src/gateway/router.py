"""密级路由：按密级选通道，preferred_model 不合规时降级并记录切换原因。

红线：L4 只能走 VPC 自部署；任何切换必须带 switched_reason，禁止静默切换。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .config import ModelSpec, RoutingConfig
from .schemas import Purpose, Sensitivity
from .usage import BudgetStatus

CHANNEL_LABEL = {
    "public_api": "通用公有云 API",
    "enterprise_api": "企业版 API",
    "vpc_self_hosted": "VPC 内自部署",
}


class NoEligibleModelError(RuntimeError):
    """该密级 + 用途下没有任何可用模型，宁可报错也不降密级。"""


@dataclass
class RoutingDecision:
    """路由结果。"""

    model: ModelSpec
    mask_required: bool
    reasons: list[str] = field(default_factory=list)

    @property
    def switched_reason(self) -> str | None:
        return "；".join(self.reasons) if self.reasons else None


class SensitivityRouter:
    """只负责「选哪个模型」，不负责调用。"""

    def __init__(self, config: RoutingConfig) -> None:
        self._config = config

    def route(
        self,
        purpose: Purpose,
        sensitivity: Sensitivity,
        preferred_model: str | None = None,
        budget: BudgetStatus | None = None,
    ) -> RoutingDecision:
        policy = self._config.policies.get(sensitivity)
        if policy is None:
            raise NoEligibleModelError(f"路由表未配置密级 {sensitivity.value}")

        candidates = self._config.models_for(purpose, policy.allowed_channels)
        if not candidates:
            raise NoEligibleModelError(
                f"密级 {sensitivity.value} + 用途 {purpose.value} 下没有可用模型，请检查路由表"
            )

        reasons: list[str] = []
        chosen = self._pick(purpose, sensitivity, preferred_model, candidates, reasons)
        chosen = self._apply_budget(purpose, candidates, chosen, budget, reasons)
        return RoutingDecision(model=chosen, mask_required=policy.mask, reasons=reasons)

    def _pick(
        self,
        purpose: Purpose,
        sensitivity: Sensitivity,
        preferred_model: str | None,
        candidates: list[ModelSpec],
        reasons: list[str],
    ) -> ModelSpec:
        allowed = {m.name for m in candidates}
        fallback = self._fallback(purpose, candidates)

        if not preferred_model:
            return fallback

        if preferred_model in allowed:
            return self._config.models[preferred_model]

        spec = self._config.models.get(preferred_model)
        if spec is None:
            reasons.append(f"首选模型 {preferred_model} 不在模型目录，改用 {fallback.name}")
        elif not spec.supports(purpose):
            reasons.append(f"首选模型 {preferred_model} 不支持 {purpose.value}，改用 {fallback.name}")
        else:
            channel = CHANNEL_LABEL.get(spec.channel.value, spec.channel.value)
            reasons.append(
                f"{sensitivity.value} 内容不允许{channel}通道，"
                f"已降级到合规通道 {CHANNEL_LABEL.get(fallback.channel.value, fallback.channel.value)}"
                f"（{preferred_model} → {fallback.name}）"
            )
        return fallback

    def _fallback(self, purpose: Purpose, candidates: list[ModelSpec]) -> ModelSpec:
        """先用该用途的默认模型，默认模型不合规时退到最便宜的合规模型。"""
        default_name = self._config.default_models.get(purpose)
        for spec in candidates:
            if spec.name == default_name:
                return spec
        return candidates[0]

    def _apply_budget(
        self,
        purpose: Purpose,
        candidates: list[ModelSpec],
        chosen: ModelSpec,
        budget: BudgetStatus | None,
        reasons: list[str],
    ) -> ModelSpec:
        if budget is None or not budget.should_degrade:
            return chosen
        low_cost_name = self._config.low_cost_models.get(purpose)
        low_cost = next((m for m in candidates if m.name == low_cost_name), None)
        if low_cost is None:
            # 配置里的低成本模型在本密级不可用时，退到当前密级最便宜的合规模型
            low_cost = candidates[0]
        if low_cost.name == chosen.name:
            return chosen
        reasons.append(
            f"部门 {budget.dept} 当期用量已达月度预算 100%"
            f"（{budget.consumed_cny:.2f}/{budget.limit_cny:.2f} 元），"
            f"自动降级到低成本模型（{chosen.name} → {low_cost.name}）"
        )
        return low_cost
