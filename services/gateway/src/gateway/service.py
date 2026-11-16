"""网关编排：路由 → 脱敏 → 调用 → 还原 → 记账。

T16 治理与审计增强：
- 调用前拒答策略检查（越权/敏感词/L4不出VPC）
- 调用后审计事件落盘（append-only JSONL，记录 user_id + 单次明细）
- generate 用途的回答注入零宽字符外发水印
"""

from __future__ import annotations

import logging

from .audit import AuditEvent, build_audit_stream_from_env
from .clients.base import ModelClientFactory
from .clients.openai_compatible import OpenAICompatibleFactory
from .config import (
    MaskingConfig,
    RoutingConfig,
    load_masking_config,
    load_routing_config,
)
from .masking import EntityMasker
from .refusal import RefusalPolicy
from .router import SensitivityRouter
from .schemas import InvokeRequest, InvokeResponse, Usage
from .usage import UNKNOWN_DEPT, BudgetGuard, UsageLedger, build_ledger_from_env
from .watermark import apply_watermark

logger = logging.getLogger("gateway.service")


class GatewayService:
    """统一模型调用入口。外部依赖（模型客户端）全部可注入。"""

    def __init__(
        self,
        routing_config: RoutingConfig | None = None,
        masking_config: MaskingConfig | None = None,
        client_factory: ModelClientFactory | None = None,
        ledger: UsageLedger | None = None,
        refusal_policy: RefusalPolicy | None = None,
        audit_stream=None,
    ) -> None:
        self.routing_config = routing_config or load_routing_config()
        self.masking_config = masking_config or load_masking_config()
        self.client_factory: ModelClientFactory = client_factory or OpenAICompatibleFactory()
        self.ledger = ledger or build_ledger_from_env()
        self.router = SensitivityRouter(self.routing_config)
        self.masker = EntityMasker(self.masking_config)
        self.budget_guard = BudgetGuard(policy=self.routing_config.budget, ledger=self.ledger)
        # T16: 拒答策略 + 审计流 + 水印
        self.refusal_policy = refusal_policy or RefusalPolicy()
        self.audit_stream = audit_stream or build_audit_stream_from_env()

    def invoke(self, request: InvokeRequest) -> InvokeResponse:
        dept = self._billing_dept(request)
        tenant = self._billing_tenant(request)
        user_id = request.principal.user_id if request.principal else "anonymous"
        user_sensitivity = request.principal.max_sensitivity if request.principal else "L1"
        req_sensitivity = request.sensitivity.value

        # T16-1: 拒答策略检查（在路由之前）
        payload_text = str(request.payload.get("text", "") or request.payload.get("question", ""))
        refusal = self.refusal_policy.check(
            user_max_sensitivity=user_sensitivity,
            request_sensitivity=req_sensitivity,
            payload_text=payload_text,
        )
        if refusal.refused:
            # 记审计事件（拒答也要记）
            self._audit(
                event="model_invoke",
                user_id=user_id,
                dept=dept,
                tenant=tenant,
                outcome="refused",
                reason=refusal.reason,
                purpose=request.purpose.value,
                sensitivity=req_sensitivity,
            )
            return InvokeResponse(
                model="",
                channel="public_api",
                usage=Usage(),
                refused=True,
                refusal_reason=refusal.reason,
            )

        # 1) 调用前判预算档位，超额则在路由阶段直接降级到低成本模型
        budget_before = self.budget_guard.check(dept, tenant=tenant)
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

        # 5) 成本用代码算并按 tenant + dept + purpose + model 记账
        usage = Usage(
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            cost_cny=decision.model.cost_cny(result.prompt_tokens, result.completion_tokens),
        )
        self.ledger.record(dept, request.purpose, decision.model.name, usage, tenant=tenant or "")

        # 6) 记账后再判一次，让刚跨过 80%/100% 的调用当次就带出告警
        budget_after = self.budget_guard.check(dept, tenant=tenant)

        if decision.switched_reason:
            logger.info(
                "模型切换：%s（user=%s）",
                decision.switched_reason,
                user_id,
            )

        # T16-2: 审计事件落盘
        self._audit(
            event="model_invoke",
            user_id=user_id,
            dept=dept,
            tenant=tenant,
            outcome="success",
            purpose=request.purpose.value,
            model=decision.model.name,
            sensitivity=req_sensitivity,
            prompt_tokens=usage.prompt_tokens,
            completion_tokens=usage.completion_tokens,
            cost_cny=usage.cost_cny,
        )

        # T16-3: generate 用途的回答注入外发水印
        # 水印只加在顶层 text 字段，output["text"] 保持原始内容（兼容按 output.text 读取的老调用方）
        answer_text = output.get("text")
        if answer_text and request.purpose.value == "generate" and user_id != "anonymous":
            answer_text = apply_watermark(answer_text, user_id)

        return InvokeResponse(
            model=decision.model.name,
            channel=decision.model.channel,
            switched_reason=decision.switched_reason,
            text=answer_text,
            output=output,
            usage=usage,
            budget_alert=budget_after.message,
        )

    def _audit(self, **kwargs) -> None:
        """写入审计事件。"""
        event = AuditEvent(**kwargs)
        self.audit_stream.append(event)

    @staticmethod
    def _billing_dept(request: InvokeRequest) -> str:
        """出账部门取主部门（principal.dept 的第一个），避免同一次调用重复计费。"""
        if request.principal is None or not request.principal.dept:
            return UNKNOWN_DEPT
        return request.principal.dept[0]

    @staticmethod
    def _billing_tenant(request: InvokeRequest) -> str:
        """出账租户。单租户部署时为空串，此时预算与统计不限租户。"""
        return request.principal.tenant if request.principal else ""
