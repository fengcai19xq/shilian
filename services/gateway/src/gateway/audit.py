"""统一审计事件流：append-only JSONL，记录每次模型调用的全链路审计事件。

T16 验收要求："append-only 审计事件流"。
现有 gateway usage 只记聚合用量（dept/tenant/purpose/model 的累计 token + cost），
不记单次调用明细、不记 user_id。本模块补齐这一缺口。

审计事件格式（contracts/platform.md §审计）:
{
  "ts": "2026-11-16T12:00:00+08:00",
  "event": "model_invoke",
  "user_id": "u1",
  "dept": "融资部",
  "tenant": "",
  "purpose": "generate",
  "model": "deepseek-chat",
  "sensitivity": "L2",
  "prompt_tokens": 100,
  "completion_tokens": 200,
  "cost_cny": 0.03,
  "outcome": "success",   # success / refused / error
  "reason": null,          # 拒答/错误原因
  "request_id": "req_xxx"  # 请求追踪 ID
}
"""

from __future__ import annotations

import json
import logging
import os
import threading
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import TextIO

from zoneinfo import ZoneInfo

logger = logging.getLogger("gateway.audit")

AUDIT_TZ = ZoneInfo("Asia/Shanghai")


@dataclass(frozen=True)
class AuditEvent:
    """单次审计事件。"""

    event: str  # model_invoke / retrieval / ingest / refuse
    user_id: str
    dept: str
    tenant: str
    outcome: str  # success / refused / error
    ts: str = ""
    purpose: str = ""
    model: str = ""
    sensitivity: str = ""
    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost_cny: float = 0.0
    reason: str | None = None
    request_id: str = ""

    def to_json(self) -> str:
        d = asdict(self)
        if not d["reason"]:
            d.pop("reason", None)
        return json.dumps(d, ensure_ascii=False, sort_keys=True)


class InMemoryAuditStream:
    """内存审计流（测试用）。"""

    def __init__(self) -> None:
        self._events: list[AuditEvent] = []

    def append(self, event: AuditEvent) -> None:
        if not event.ts:
            event = AuditEvent(
                **{**asdict(event), "ts": datetime.now(tz=AUDIT_TZ).isoformat()}
            )
        self._events.append(event)

    def events(self) -> list[AuditEvent]:
        return list(self._events)

    def clear(self) -> None:
        self._events.clear()


class JsonlAuditStream:
    """Append-only JSONL 文件审计流。

    红线：
    1. 只追加不覆盖（'a' 模式打开）
    2. 单进程内线程安全（Lock）
    3. 文件权限 0600
    4. 每条事件一行 JSON，flush 后才返回
    """

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        # 确保文件存在
        if not self.path.exists():
            self.path.touch(mode=0o600)
        else:
            os.chmod(self.path, 0o600)
        self._fp: TextIO | None = None

    def _ensure_open(self) -> TextIO:
        if self._fp is None or self._fp.closed:
            self._fp = open(self.path, "a", encoding="utf-8")
        return self._fp

    def append(self, event: AuditEvent) -> None:
        if not event.ts:
            event = AuditEvent(
                **{**asdict(event), "ts": datetime.now(tz=AUDIT_TZ).isoformat()}
            )
        line = event.to_json() + "\n"
        with self._lock:
            fp = self._ensure_open()
            fp.write(line)
            fp.flush()

    def close(self) -> None:
        if self._fp and not self._fp.closed:
            self._fp.close()

    def read_all(self) -> list[AuditEvent]:
        """读取全部审计事件（仅供审计查询端点使用）。"""
        events: list[AuditEvent] = []
        if not self.path.exists():
            return events
        with open(self.path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                    events.append(AuditEvent(**d))
                except (json.JSONDecodeError, TypeError):
                    continue
        return events


def build_audit_stream_from_env() -> InMemoryAuditStream | JsonlAuditStream:
    """默认内存流；设置 GATEWAY_AUDIT_LOG_PATH 时落 JSONL 文件。"""
    path = os.environ.get("GATEWAY_AUDIT_LOG_PATH", "").strip()
    return JsonlAuditStream(path) if path else InMemoryAuditStream()
