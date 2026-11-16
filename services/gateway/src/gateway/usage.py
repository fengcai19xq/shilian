"""用量与成本：按 dept + purpose + model 累计，并给出预算档位判断。

默认实现是进程内内存账本（便于单测与单机部署），需要落库时替换 UsageLedger 即可，
网关只依赖 record / dept_total 两个方法。
"""

from __future__ import annotations

import logging
import os
import sqlite3
import threading
from contextlib import closing
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path
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
    """出账维度：周期 + 租户 + 部门 + 用途 + 模型。

    tenant 缺省空串表示单租户部署（contracts/platform.md §5.1 的多租户出账键）。
    """

    period: str
    dept: str
    purpose: Purpose
    model: str
    tenant: str = ""


@dataclass
class UsageRecord:
    """某一维度上的累计值。"""

    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost_cny: float = 0.0
    calls: int = 0


def build_ledger_from_env() -> "UsageLedger | SqliteUsageLedger":
    """默认内存账本；设置 GATEWAY_USAGE_DB_PATH 时落 SQLite，重启不丢预算统计。"""
    path = os.environ.get("GATEWAY_USAGE_DB_PATH", "").strip()
    return SqliteUsageLedger(path) if path else UsageLedger()


class UsageLedger:
    """内存账本。"""

    def __init__(self) -> None:
        self._records: dict[UsageKey, UsageRecord] = {}

    def record(
        self,
        dept: str,
        purpose: Purpose,
        model: str,
        usage: Usage,
        period: str | None = None,
        tenant: str = "",
    ) -> None:
        key = UsageKey(period=period or current_period(), dept=dept, purpose=purpose, model=model,
                       tenant=tenant)
        item = self._records.setdefault(key, UsageRecord())
        item.prompt_tokens += usage.prompt_tokens
        item.completion_tokens += usage.completion_tokens
        item.cost_cny = round(item.cost_cny + usage.cost_cny, 6)
        item.calls += 1

    def get(
        self,
        dept: str,
        purpose: Purpose,
        model: str,
        period: str | None = None,
        tenant: str = "",
    ) -> UsageRecord:
        key = UsageKey(period=period or current_period(), dept=dept, purpose=purpose, model=model,
                       tenant=tenant)
        return self._records.get(key, UsageRecord())

    def dept_total(self, dept: str, period: str | None = None, tenant: str | None = None) -> float:
        """某部门当期总花费（元）。

        tenant=None 表示不限租户（单租户部署与既有调用方的默认行为）；
        传入具体租户则只统计该租户。
        """
        target = period or current_period()
        return round(
            sum(
                v.cost_cny
                for k, v in self._records.items()
                if k.dept == dept and k.period == target and (tenant is None or k.tenant == tenant)
            ),
            6,
        )

    def snapshot(self, period: str | None = None) -> dict[UsageKey, UsageRecord]:
        target = period or current_period()
        return {k: v for k, v in self._records.items() if k.period == target}


class SqliteUsageLedger:
    """SQLite 账本：单文件、跨重启保留按周期/租户/部门/用途/模型的累计值。

    生产多副本共享同一个数据库文件不现实，应换 PostgreSQL/Redis；但单机测试环境
    与单进程部署下，它比内存账本更接近“预算真的不会因重启归零”。
    """

    _SCHEMA = """
    CREATE TABLE IF NOT EXISTS usage (
        period TEXT NOT NULL,
        tenant TEXT NOT NULL DEFAULT '',
        dept TEXT NOT NULL,
        purpose TEXT NOT NULL,
        model TEXT NOT NULL,
        prompt_tokens INTEGER NOT NULL DEFAULT 0,
        completion_tokens INTEGER NOT NULL DEFAULT 0,
        cost_cny REAL NOT NULL DEFAULT 0,
        calls INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (period, tenant, dept, purpose, model)
    )
    """

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with closing(self._connect()) as conn:
            conn.execute(self._SCHEMA)
            conn.commit()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        return conn

    def record(
        self,
        dept: str,
        purpose: Purpose,
        model: str,
        usage: Usage,
        period: str | None = None,
        tenant: str = "",
    ) -> None:
        key = (period or current_period(), tenant, dept, purpose.value, model)
        with self._lock, closing(self._connect()) as conn:
            conn.execute(
                """
                INSERT INTO usage
                    (period, tenant, dept, purpose, model, prompt_tokens,
                     completion_tokens, cost_cny, calls)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT(period, tenant, dept, purpose, model) DO UPDATE SET
                    prompt_tokens = prompt_tokens + excluded.prompt_tokens,
                    completion_tokens = completion_tokens + excluded.completion_tokens,
                    cost_cny = ROUND(cost_cny + excluded.cost_cny, 6),
                    calls = calls + 1
                """,
                (*key, usage.prompt_tokens, usage.completion_tokens, usage.cost_cny),
            )
            conn.commit()

    def get(
        self,
        dept: str,
        purpose: Purpose,
        model: str,
        period: str | None = None,
        tenant: str = "",
    ) -> UsageRecord:
        with closing(self._connect()) as conn:
            row = conn.execute(
                """
                SELECT prompt_tokens, completion_tokens, cost_cny, calls
                FROM usage WHERE period=? AND tenant=? AND dept=? AND purpose=? AND model=?
                """,
                (period or current_period(), tenant, dept, purpose.value, model),
            ).fetchone()
        if row is None:
            return UsageRecord()
        return UsageRecord(
            prompt_tokens=row["prompt_tokens"],
            completion_tokens=row["completion_tokens"],
            cost_cny=round(row["cost_cny"], 6),
            calls=row["calls"],
        )

    def dept_total(self, dept: str, period: str | None = None, tenant: str | None = None) -> float:
        target = period or current_period()
        if tenant is None:
            sql = "SELECT COALESCE(SUM(cost_cny), 0) AS total FROM usage WHERE period=? AND dept=?"
            args: tuple = (target, dept)
        else:
            sql = (
                "SELECT COALESCE(SUM(cost_cny), 0) AS total FROM usage "
                "WHERE period=? AND dept=? AND tenant=?"
            )
            args = (target, dept, tenant)
        with closing(self._connect()) as conn:
            row = conn.execute(sql, args).fetchone()
        return round(float(row["total"]), 6)

    def snapshot(self, period: str | None = None) -> dict[UsageKey, UsageRecord]:
        target = period or current_period()
        with closing(self._connect()) as conn:
            rows = conn.execute(
                "SELECT * FROM usage WHERE period=?", (target,)
            ).fetchall()
        return {
            UsageKey(
                period=row["period"],
                dept=row["dept"],
                purpose=Purpose(row["purpose"]),
                model=row["model"],
                tenant=row["tenant"],
            ): UsageRecord(
                prompt_tokens=row["prompt_tokens"],
                completion_tokens=row["completion_tokens"],
                cost_cny=round(row["cost_cny"], 6),
                calls=row["calls"],
            )
            for row in rows
        }


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
    tenant: str = ""


@dataclass
class BudgetGuard:
    """按预算策略判断是否告警 / 是否强制降级到低成本模型。"""

    policy: BudgetPolicy
    ledger: UsageLedger = field(default_factory=UsageLedger)

    def check(self, dept: str, period: str | None = None, tenant: str | None = None) -> BudgetStatus:
        """预算额度仍按部门配置（只增不改）；tenant 只决定统计口径。

        tenant=None → 不限租户（单租户部署 / 既有调用方行为不变）；
        tenant 传具体值 → 只统计该租户在该部门的用量，避免跨租户互相挤占预算。
        """
        limit = self.policy.limit_for(dept)
        consumed = self.ledger.dept_total(dept, period, tenant=tenant)
        if limit <= 0:
            return BudgetStatus(dept=dept, limit_cny=limit, consumed_cny=consumed, tenant=tenant or "")

        ratio = consumed / limit
        status = BudgetStatus(dept=dept, limit_cny=limit, consumed_cny=consumed, ratio=ratio,
                              tenant=tenant or "")
        who = f"租户 {tenant} / 部门 {dept}" if tenant else f"部门 {dept}"
        if ratio >= self.policy.degrade_ratio:
            status.should_degrade = True
            status.should_warn = True
            status.message = (
                f"{who} 当期用量已达月度预算 100%（{consumed:.2f}/{limit:.2f} 元），"
                "已自动降级到低成本模型，请通知数智化中心"
            )
            logger.error(status.message)
        elif ratio >= self.policy.warn_ratio:
            status.should_warn = True
            status.message = (
                f"{who} 当期用量已达月度预算 "
                f"{self.policy.warn_ratio:.0%}（{consumed:.2f}/{limit:.2f} 元）"
            )
            logger.warning(status.message)
        return status
