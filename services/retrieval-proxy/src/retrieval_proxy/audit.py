"""审计事件与写出接口。每次检索必须产生一条审计事件。"""

from __future__ import annotations

import json
import os
import threading
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
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
    # contracts/platform.md §2.4：审计事件必须带 tenant_id
    tenant: str = "default"
    # ok / error；检索后端失败也要留痕，不能只在成功时写审计
    outcome: str = "ok"


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


class JsonlAuditSink:
    """追加写入 JSONL 文件，进程重启后审计仍在。

    这是“最低可用”的持久化实现，不替代生产审计库；但比内存态强：
    - 只追加，不覆盖；
    - 单进程内加锁，避免并发写串行化；
    - 文件权限 0600，避免同机其他用户读取查询原文。
    """

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        os.close(fd)
        self._lock = threading.Lock()

    def write(self, event: AuditEvent) -> None:
        line = json.dumps(asdict(event), ensure_ascii=False, default=str)
        with self._lock:
            with open(self.path, "a", encoding="utf-8") as fp:
                fp.write(line + "\n")
                fp.flush()
