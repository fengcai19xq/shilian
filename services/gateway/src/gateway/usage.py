"""用量与成本：按 dept + purpose + model 累计，并给出预算档位判断。

默认实现是进程内内存账本（便于单测与单机部署），需要落库时替换 UsageLedger 即可，
网关只依赖 record / dept_total 两个方法。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import date, datetime
from zoneinfo import ZoneInfo

from .config import BudgetPolicy
from .schemas import Purpose, Usage

logger = logging.getLogger("gateway.usage")

UNKNOWN_DEPT = "未分配部门"
BILLING_TZ = ZoneInfo("Asia/Shanghai")


def current_period(today: date | None = None) -> str:
    """出账周期，按北京时间自然月。"""
    day = today or datetime.now(tz=BILLING_TZ).date()
    return f"{day.year:04d}-{day.month:02d}"


@dataclass(frozen=True)
class UsageKey:
    """出账维度：周期 + 部门 + 用途 + 模型。"""

    period: str
    dept: str
    purpose: Purpose
    model: str


@dataclass
class UsageRecord:
    """某一维度上的累计值。"""

    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost_cny: float = 0.0
    calls: int = 0


class UsageLedger:
    """内存账本。"""

    def __init__(self) -> None:
        self._records: dict[UsageKey, UsageRecord] = {}

    def record(
        self, dept: str, purpose: Purpose, model: str, usage: Usage, period: str | None = None
    ) -> None:
        key = UsageKey(period=period or current_period(), dept=dept, purpose=purpose, model=model)
        item = self._records.setdefault(key, UsageRecord())
        item.prompt_tokens += usage.prompt_tokens
        item.completion_tokens += usage.completion_tokens
        item.cost_cny = round(item.cost_cny + usage.cost_cny, 6)
        item.calls += 1

    def get(self, dept: str, purpose: Purpose, model: str, period: str | None = None) -> UsageRecord:
        key = UsageKey(period=period or current_period(), dept=dept, purpose=purpose, model=model)
        return self._records.get(key, UsageRecord())

    def dept_total(self, dept: str, period: str | None = None) -> float:
        """某部门当期总花费（元）。"""
        target = period or current_period()
        return round(
            sum(v.cost_cny for k, v in self._records.items() if k.dept == dept and k.period == target),
            6,
        )

    def snapshot(self, period: str | None = None) -> dict[UsageKey, UsageRecord]:
        target = period or current_period()
        return {k: v for k, v in self._records.items() if k.period == target}


@dataclass
class BudgetStatus:
    """预算档位判定结果。"""

    dept: str
    limit_cny: float
    consumed_cny: float
    ratio: float = 0.0
    should_warn: bool = False
    should_degrade: bool = False
    message: str | None = None


@dataclass
class BudgetGuard:
    """按预算策略判断是否告警 / 是否强制降级到低成本模型。"""

    policy: BudgetPolicy
    ledger: UsageLedger = field(default_factory=UsageLedger)

    def check(self, dept: str, period: str | None = None) -> BudgetStatus:
        limit = self.policy.limit_for(dept)
        consumed = self.ledger.dept_total(dept, period)
        if limit <= 0:
            return BudgetStatus(dept=dept, limit_cny=limit, consumed_cny=consumed)

        ratio = consumed / limit
        status = BudgetStatus(dept=dept, limit_cny=limit, consumed_cny=consumed, ratio=ratio)
        if ratio >= self.policy.degrade_ratio:
            status.should_degrade = True
            status.should_warn = True
            status.message = (
                f"部门 {dept} 当期用量已达月度预算 100%（{consumed:.2f}/{limit:.2f} 元），"
                "已自动降级到低成本模型，请通知数智化中心"
            )
            logger.error(status.message)
        elif ratio >= self.policy.warn_ratio:
            status.should_warn = True
            status.message = (
                f"部门 {dept} 当期用量已达月度预算 "
                f"{self.policy.warn_ratio:.0%}（{consumed:.2f}/{limit:.2f} 元）"
            )
            logger.warning(status.message)
        return status
