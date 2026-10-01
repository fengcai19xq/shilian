"""网关编排：路由 → 脱敏 → 调用 → 还原 → 记账。"""

from __future__ import annotations

import logging

from .clients.base import ModelClientFactory
from .clients.openai_compatible import OpenAICompatibleFactory
from .config import (
    MaskingConfig,
    RoutingConfig,
    load_masking_config,
    load_routing_config,
)
from .masking import EntityMasker
from .router import SensitivityRouter
from .schemas import InvokeRequest, InvokeResponse, Usage
from .usage import UNKNOWN_DEPT, BudgetGuard, UsageLedger

logger = logging.getLogger("gateway.service")


class GatewayService:
    """统一模型调用入口。外部依赖（模型客户端）全部可注入。"""

    def __init__(
        self,
        routing_config: RoutingConfig | None = None,
        masking_config: MaskingConfig | None = None,
        client_factory: ModelClientFactory | None = None,
        ledger: UsageLedger | None = None,
    ) -> None:
        self.routing_config = routing_config or load_routing_config()
        self.masking_config = masking_config or load_masking_config()
        self.client_factory: ModelClientFactory = client_factory or OpenAICompatibleFactory()
        self.ledger = ledger or UsageLedger()
        self.router = SensitivityRouter(self.routing_config)
        self.masker = EntityMasker(self.masking_config)
        self.budget_guard = BudgetGuard(policy=self.routing_config.budget, ledger=self.ledger)

    def invoke(self, request: InvokeRequest) -> InvokeResponse:
        dept = self._billing_dept(request)

        # 1) 调用前判预算档位，超额则在路由阶段直接降级到低成本模型
        budget_before = self.budget_guard.check(dept)
        decision = self.router.route(
            purpose=request.purpose,
            sensitivity=request.sensitivity,
            preferred_model=request.preferred_model,
            budget=budget_before,
        )

        # 2) L3 在请求体离开本进程之前完成实体脱敏
        payload = request.payload
        mapping: dict[str, str] = {}
        if decision.mask_required:
            masked = self.masker.mask(payload)
            payload, mapping = masked.payload, masked.mapping

        # 3) 调用模型
        client = self.client_factory.get(decision.model)
        result = client.invoke(decision.model, request.purpose, payload)

        # 4) 返回后还原占位符
        output = self.masker.restore(result.output, mapping) if mapping else result.output

        # 5) 成本用代码算并按 dept + purpose + model 记账
        usage = Usage(
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            cost_cny=decision.model.cost_cny(result.prompt_tokens, result.completion_tokens),
        )
        self.ledger.record(dept, request.purpose, decision.model.name, usage)

        # 6) 记账后再判一次，让刚跨过 80%/100% 的调用当次就带出告警
        budget_after = self.budget_guard.check(dept)

        if decision.switched_reason:
            logger.info("模型切换：%s（user=%s）", decision.switched_reason, request.principal.user_id)

        return InvokeResponse(
            model=decision.model.name,
            channel=decision.model.channel,
            switched_reason=decision.switched_reason,
            output=output,
            usage=usage,
            budget_alert=budget_after.message,
        )

    @staticmethod
    def _billing_dept(request: InvokeRequest) -> str:
        """出账部门取主部门（principal.dept 的第一个），避免同一次调用重复计费。"""
        return request.principal.dept[0] if request.principal.dept else UNKNOWN_DEPT
