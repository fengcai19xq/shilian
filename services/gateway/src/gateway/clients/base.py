"""模型客户端统一接口。各家模型差异都收敛在实现里，网关只认这个协议。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

from ..config import ModelSpec
from ..schemas import Purpose


@dataclass
class ClientResult:
    """客户端调用结果。token 数由客户端返回，成本由网关按价目表算。"""

    output: dict[str, Any] = field(default_factory=dict)
    prompt_tokens: int = 0
    completion_tokens: int = 0


@runtime_checkable
class ModelClient(Protocol):
    """一次模型调用。payload 已按密级处理过（L3 已脱敏）。"""

    def invoke(self, spec: ModelSpec, purpose: Purpose, payload: dict[str, Any]) -> ClientResult: ...


@runtime_checkable
class ModelClientFactory(Protocol):
    """按模型取客户端，便于测试注入 fake。"""

    def get(self, spec: ModelSpec) -> ModelClient: ...
