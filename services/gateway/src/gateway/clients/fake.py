"""测试用假客户端：不发网络请求，记录每次收到的载荷，便于断言脱敏是否生效。"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from ..config import ModelSpec
from ..schemas import Purpose
from .base import ClientResult


@dataclass
class FakeCall:
    """一次被记录的调用——这里的 payload 就是「离开进程」的内容。"""

    model: str
    endpoint: str
    purpose: Purpose
    payload: dict[str, Any]


@dataclass
class FakeModelClient:
    """可配置 token 数与返回文本的假客户端。"""

    calls: list[FakeCall] = field(default_factory=list)
    prompt_tokens: int = 100
    completion_tokens: int = 50
    responder: Callable[[ModelSpec, Purpose, dict[str, Any]], dict[str, Any]] | None = None

    def invoke(self, spec: ModelSpec, purpose: Purpose, payload: dict[str, Any]) -> ClientResult:
        self.calls.append(FakeCall(model=spec.name, endpoint=spec.endpoint, purpose=purpose, payload=payload))
        if self.responder is not None:
            output = self.responder(spec, purpose, payload)
        elif purpose is Purpose.embed:
            output = {"embeddings": [[0.1, 0.2, 0.3]]}
        elif purpose is Purpose.rerank:
            output = {"results": [{"index": 0, "score": 0.9}]}
        else:
            output = {"text": f"[fake:{spec.name}] " + str(payload.get("prompt", ""))}
        return ClientResult(
            output=output,
            prompt_tokens=self.prompt_tokens,
            completion_tokens=self.completion_tokens,
        )


@dataclass
class FakeClientFactory:
    """所有模型共用一个 FakeModelClient，方便集中断言调用记录。"""

    client: FakeModelClient = field(default_factory=FakeModelClient)

    def get(self, spec: ModelSpec) -> FakeModelClient:
        return self.client

    @property
    def calls(self) -> list[FakeCall]:
        return self.client.calls
