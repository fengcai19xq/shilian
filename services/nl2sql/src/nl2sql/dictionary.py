"""指标口径字典：从 metrics.yaml 加载指标定义，提供按 ID 查询。"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


@dataclass(frozen=True)
class Indicator:
    """单个经营指标定义。"""

    id: str
    name: str
    description: str
    unit: str
    sensitivity: str  # L1 / L2 / L3 / L4
    view: str
    sql_template: str
    required_params: list[str]

    def render_sql(self, params: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        """返回 (最终 SQL, 绑定参数)。

        SQL 模板中的 ``{view}`` 占位符用 view 字段值安全替换
        （view 名来自字典配置，不是用户输入，不存在注入风险）。
        其余参数通过 :param 占位符传递给驱动层。
        """
        sql = self.sql_template.replace("{view}", self.view)
        bound: dict[str, Any] = {}
        for p in self.required_params:
            if p not in params:
                raise ValueError(f"缺少必需参数: {p}")
            bound[p] = params[p]
        return sql, bound


_SENSITIVITY_ORDER = {"L1": 1, "L2": 2, "L3": 3, "L4": 4}


class MetricDictionary:
    """指标口径字典。"""

    def __init__(self, indicators: list[Indicator]) -> None:
        self._by_id: dict[str, Indicator] = {i.id: i for i in indicators}

    @classmethod
    def from_yaml(cls, path: str | Path) -> "MetricDictionary":
        with open(path, encoding="utf-8") as f:
            raw = yaml.safe_load(f)
        items = raw.get("indicators", [])
        return cls([Indicator(**item) for item in items])

    def get(self, indicator_id: str) -> Indicator | None:
        return self._by_id.get(indicator_id)

    def list_all(self) -> list[Indicator]:
        return list(self._by_id.values())

    def can_access(self, indicator: Indicator, user_max_sensitivity: str) -> bool:
        """用户的密级是否 >= 指标要求的密级。"""
        user_level = _SENSITIVITY_ORDER.get(user_max_sensitivity, 0)
        required = _SENSITIVITY_ORDER.get(indicator.sensitivity, 99)
        return user_level >= required
