"""配置加载：路由表、模型目录、预算、脱敏词表，全部来自 yaml，可替换。"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from .schemas import Channel, Purpose, Sensitivity

CONF_DIR = Path(__file__).parent / "conf"
DEFAULT_ROUTING_PATH = CONF_DIR / "routing.yaml"
DEFAULT_MASKING_PATH = CONF_DIR / "masking.yaml"


@dataclass(frozen=True)
class ModelSpec:
    """一个可调用模型的静态描述。密钥只记环境变量名，不记值。"""

    name: str
    channel: Channel
    purposes: tuple[Purpose, ...]
    endpoint: str
    api_key_env: str
    price_prompt_cny_per_1k: float = 0.0
    price_completion_cny_per_1k: float = 0.0

    def supports(self, purpose: Purpose) -> bool:
        return purpose in self.purposes

    def cost_cny(self, prompt_tokens: int, completion_tokens: int) -> float:
        """成本用代码算，不交给模型；保留 6 位小数避免浮点噪声。"""
        cost = (
            prompt_tokens / 1000 * self.price_prompt_cny_per_1k
            + completion_tokens / 1000 * self.price_completion_cny_per_1k
        )
        return round(cost, 6)


@dataclass(frozen=True)
class SensitivityPolicy:
    """某一密级允许的通道，以及是否需要调用前脱敏。"""

    allowed_channels: tuple[Channel, ...]
    mask: bool


@dataclass(frozen=True)
class BudgetPolicy:
    """月度预算策略。"""

    warn_ratio: float = 0.8
    degrade_ratio: float = 1.0
    monthly_cny_default: float = 0.0
    monthly_cny_by_dept: dict[str, float] = field(default_factory=dict)

    def limit_for(self, dept: str) -> float:
        return self.monthly_cny_by_dept.get(dept, self.monthly_cny_default)


@dataclass(frozen=True)
class RoutingConfig:
    """路由表整体配置。"""

    policies: dict[Sensitivity, SensitivityPolicy]
    models: dict[str, ModelSpec]
    default_models: dict[Purpose, str]
    low_cost_models: dict[Purpose, str]
    budget: BudgetPolicy

    def models_for(self, purpose: Purpose, channels: tuple[Channel, ...]) -> list[ModelSpec]:
        """按 purpose 与允许通道筛选候选模型，成本低的排前面。"""
        candidates = [m for m in self.models.values() if m.supports(purpose) and m.channel in channels]
        return sorted(candidates, key=lambda m: m.price_prompt_cny_per_1k + m.price_completion_cny_per_1k)


@dataclass(frozen=True)
class MaskingConfig:
    """脱敏词表：字面量词表 + 正则规则。"""

    literals: dict[str, tuple[str, ...]] = field(default_factory=dict)
    patterns: tuple[tuple[str, str], ...] = ()


def _load_yaml(path: str | Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as fp:
        return yaml.safe_load(fp) or {}


def load_routing_config(path: str | Path | None = None) -> RoutingConfig:
    raw = _load_yaml(path or DEFAULT_ROUTING_PATH)

    policies: dict[Sensitivity, SensitivityPolicy] = {}
    for level, item in (raw.get("sensitivity_policy") or {}).items():
        policies[Sensitivity(level)] = SensitivityPolicy(
            allowed_channels=tuple(Channel(c) for c in item.get("allowed_channels", [])),
            mask=bool(item.get("mask", False)),
        )

    models: dict[str, ModelSpec] = {}
    for item in raw.get("models") or []:
        spec = ModelSpec(
            name=item["name"],
            channel=Channel(item["channel"]),
            purposes=tuple(Purpose(p) for p in item.get("purposes", [])),
            endpoint=item.get("endpoint", ""),
            api_key_env=item.get("api_key_env", ""),
            price_prompt_cny_per_1k=float(item.get("price_prompt_cny_per_1k", 0.0)),
            price_completion_cny_per_1k=float(item.get("price_completion_cny_per_1k", 0.0)),
        )
        models[spec.name] = spec

    budget_raw = raw.get("budget") or {}
    budget = BudgetPolicy(
        warn_ratio=float(budget_raw.get("warn_ratio", 0.8)),
        degrade_ratio=float(budget_raw.get("degrade_ratio", 1.0)),
        monthly_cny_default=float(budget_raw.get("monthly_cny_default", 0.0)),
        monthly_cny_by_dept={k: float(v) for k, v in (budget_raw.get("monthly_cny_by_dept") or {}).items()},
    )

    return RoutingConfig(
        policies=policies,
        models=models,
        default_models={Purpose(k): v for k, v in (raw.get("default_models") or {}).items()},
        low_cost_models={Purpose(k): v for k, v in (raw.get("low_cost_models") or {}).items()},
        budget=budget,
    )


def load_masking_config(path: str | Path | None = None) -> MaskingConfig:
    raw = _load_yaml(path or DEFAULT_MASKING_PATH)
    literals = {k: tuple(v or ()) for k, v in (raw.get("literals") or {}).items()}
    patterns = tuple((p["name"], p["regex"]) for p in (raw.get("patterns") or []))
    return MaskingConfig(literals=literals, patterns=patterns)
