"""内存态存储：清单、任务、审计事件。

先用进程内实现，接口收敛在这里，后续换 DB 不影响上层。
"""

from __future__ import annotations

import datetime as _dt
import threading
import uuid

from .models import AuditEvent, Checklist, ChecklistItem, ItemResult, MatchTask


def _now() -> str:
    return _dt.datetime.now(_dt.UTC).isoformat(timespec="seconds")


class MemoryStore:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self.checklists: dict[str, Checklist] = {}
        self.tasks: dict[str, MatchTask] = {}
        self.results: dict[str, dict[str, ItemResult]] = {}  # checklist_id -> item_id -> 结果
        self.audit: list[AuditEvent] = []

    # ---------- 清单 ----------

    def save_checklist(self, checklist: Checklist) -> None:
        with self._lock:
            self.checklists[checklist.checklist_id] = checklist
            self.results.setdefault(checklist.checklist_id, {})

    def get_checklist(self, checklist_id: str) -> Checklist | None:
        return self.checklists.get(checklist_id)

    def find_item(self, item_id: str) -> tuple[Checklist, ChecklistItem] | None:
        for checklist in self.checklists.values():
            for item in checklist.items:
                if item.item_id == item_id:
                    return checklist, item
        return None

    # ---------- 任务与结果 ----------

    def create_task(self, checklist_id: str, total: int) -> MatchTask:
        task = MatchTask(task_id=f"tk_{uuid.uuid4().hex[:8]}", checklist_id=checklist_id, total=total)
        with self._lock:
            self.tasks[task.task_id] = task
        return task

    def get_task(self, task_id: str) -> MatchTask | None:
        return self.tasks.get(task_id)

    def put_result(self, checklist_id: str, result: ItemResult) -> None:
        with self._lock:
            self.results.setdefault(checklist_id, {})[result.item_id] = result

    def get_result(self, checklist_id: str, item_id: str) -> ItemResult | None:
        return self.results.get(checklist_id, {}).get(item_id)

    def list_results(self, checklist_id: str) -> list[ItemResult]:
        return list(self.results.get(checklist_id, {}).values())

    # ---------- 审计 ----------

    def add_audit(self, checklist_id: str, action: str, *, item_id: str | None = None,
                  operator: str = "system", detail: dict | None = None) -> AuditEvent:
        event = AuditEvent(
            event_id=f"ev_{uuid.uuid4().hex[:8]}",
            ts=_now(),
            checklist_id=checklist_id,
            item_id=item_id,
            action=action,
            operator=operator,
            detail=detail or {},
        )
        with self._lock:
            self.audit.append(event)
        return event

    def list_audit(self, checklist_id: str | None = None) -> list[AuditEvent]:
        if checklist_id is None:
            return list(self.audit)
        return [e for e in self.audit if e.checklist_id == checklist_id]
