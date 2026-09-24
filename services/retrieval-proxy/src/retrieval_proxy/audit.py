"""审计事件与写出接口。每次检索必须产生一条审计事件。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Protocol


@dataclass(frozen=True)
class AuditEvent:
    """who / query / scope / hit_doc_ids / ts。"""

    who: str
    query: str
    scope: list[str]
    hit_doc_ids: list[str]
    ts: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    mode: str = "full"


class AuditSink(Protocol):
    """审计写出接口；生产环境可落库或写日志总线，测试用内存实现。"""

    def write(self, event: AuditEvent) -> None:  # pragma: no cover - 协议声明
        ...


class InMemoryAuditSink:
    """内存审计写出，用于本地开发与测试。"""

    def __init__(self) -> None:
        self.events: list[AuditEvent] = []

    def write(self, event: AuditEvent) -> None:
        self.events.append(event)
