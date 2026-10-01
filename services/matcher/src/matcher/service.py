"""匹配服务编排：解析入库、异步匹配、人工确认、导出 manifest。

外部依赖（召回、decide）全部通过构造参数注入。
"""

from __future__ import annotations

import datetime as _dt

from .config import MatcherConfig
from .models import (
    Checklist,
    ItemResult,
    ItemStatus,
    Manifest,
    ManifestEntry,
    ManifestFile,
    MatchTask,
)
from .parsing import ChecklistParser
from .ports import CandidateProvider, DecideProvider
from .scoring import score_item
from .store import MemoryStore
from .typedict import TypeDictionary


class NotFoundError(LookupError):
    """资源不存在。"""


class StateError(ValueError):
    """当前状态不允许该操作。"""


class MatcherService:
    def __init__(
        self,
        candidates: CandidateProvider,
        decide: DecideProvider,
        config: MatcherConfig | None = None,
        store: MemoryStore | None = None,
        dictionary: TypeDictionary | None = None,
    ):
        self.config = config or MatcherConfig()
        self.candidates = candidates
        self.decide = decide
        self.store = store or MemoryStore()
        self.dictionary = dictionary or TypeDictionary.load(self.config.types_path)
        self.parser = ChecklistParser(self.config.parser, self.dictionary)

    # ---------- 上传解析 ----------

    def upload(self, content: bytes | str, filename: str = "", title: str = "",
               operator: str = "system") -> Checklist:
        checklist = self.parser.parse(content, filename=filename, title=title)
        self.store.save_checklist(checklist)
        unknown = [i.no for i in checklist.items if i.type_status == "unknown"]
        self.store.add_audit(
            checklist.checklist_id, "checklist_uploaded", operator=operator,
            detail={"filename": filename, "items": len(checklist.items), "unknown_type_nos": unknown},
        )
        return checklist

    # ---------- 匹配 ----------

    def create_task(self, checklist_id: str) -> MatchTask:
        checklist = self._checklist(checklist_id)
        task = self.store.create_task(checklist_id, total=len(checklist.items))
        self.store.add_audit(checklist_id, "match_task_created", detail={"task_id": task.task_id})
        return task

    def run_task(self, task_id: str) -> MatchTask:
        """执行匹配任务。API 层放到后台执行，测试里可直接同步调用。"""
        task = self.store.get_task(task_id)
        if task is None:
            raise NotFoundError(task_id)
        checklist = self._checklist(task.checklist_id)
        task.state = "running"
        try:
            for item in checklist.items:
                prev = self.store.get_result(checklist.checklist_id, item.item_id)
                if prev is not None and prev.confirmed_by:
                    # 人工确认过的项不被机器结果覆盖
                    task.results.append(prev)
                else:
                    result = score_item(item, self.candidates.recall(item), self.decide,
                                        self.config.scoring)
                    self.store.put_result(checklist.checklist_id, result)
                    self.store.add_audit(
                        checklist.checklist_id, "item_status_changed", item_id=item.item_id,
                        detail={
                            "from": prev.status.value if prev else None,
                            "to": result.status.value,
                            "score": result.score,
                            "task_id": task.task_id,
                        },
                    )
                    task.results.append(result)
                task.done += 1
            task.state = "succeeded"
        except Exception as exc:  # noqa: BLE001 - 任务失败需要落状态而不是抛给后台线程
            task.state = "failed"
            task.error = f"{type(exc).__name__}: {exc}"
            self.store.add_audit(checklist.checklist_id, "match_task_failed",
                                 detail={"task_id": task.task_id, "error": task.error})
        return task

    def get_task(self, task_id: str) -> MatchTask:
        task = self.store.get_task(task_id)
        if task is None:
            raise NotFoundError(task_id)
        return task

    # ---------- 人工确认 ----------

    def confirm(self, item_id: str, doc_ids: list[str], operator: str, note: str = "") -> ItemResult:
        """人工确认绑定：doc_ids 非空 -> matched；为空 -> 确认缺件 missing。"""
        if not operator:
            raise StateError("人工确认必须记录操作人")
        found = self.store.find_item(item_id)
        if found is None:
            raise NotFoundError(item_id)
        checklist, item = found
        prev = self.store.get_result(checklist.checklist_id, item_id)
        new_status = ItemStatus.MATCHED if doc_ids else ItemStatus.MISSING
        result = ItemResult(
            item_id=item.item_id,
            no=item.no,
            std_type=item.std_type,
            std_name=item.std_name,
            status=new_status,
            score=prev.score if prev else 0.0,
            candidates=prev.candidates if prev else [],
            bound_doc_ids=list(dict.fromkeys(doc_ids)),
            confirmed_by=operator,
            note=note,
        )
        self.store.put_result(checklist.checklist_id, result)
        self.store.add_audit(
            checklist.checklist_id, "item_confirmed", item_id=item_id, operator=operator,
            detail={
                "from": prev.status.value if prev else None,
                "to": new_status.value,
                "doc_ids": result.bound_doc_ids,
                "prev_doc_ids": prev.bound_doc_ids if prev else [],
                "note": note,
            },
        )
        return result

    # ---------- manifest ----------

    def manifest(self, checklist_id: str, title: str | None = None, watermark: str = "",
                 version: int = 1) -> Manifest:
        """导出给 packager 的 manifest（contracts/packaging.md）。

        只有 matched 项带文件；pending / missing 不自动入册。
        """
        checklist = self._checklist(checklist_id)
        entries: list[ManifestEntry] = []
        for item in checklist.items:
            result = self.store.get_result(checklist_id, item.item_id)
            std_name = item.std_name or item.raw_text
            if result is None:
                entries.append(ManifestEntry(no=item.no, std_name=std_name,
                                             status=ItemStatus.MISSING, note="尚未匹配"))
                continue
            files: list[ManifestFile] = []
            if result.status is ItemStatus.MATCHED:
                uris = {c.doc_id: c.uri for c in result.candidates}
                for order, doc_id in enumerate(result.bound_doc_ids, start=1):
                    files.append(ManifestFile(uri=uris.get(doc_id) or f"doc://{doc_id}", order=order))
            entries.append(ManifestEntry(no=item.no, std_name=std_name, files=files,
                                         status=result.status, note=result.note))
        suffix = checklist_id.removeprefix("cl_")
        return Manifest(
            package_id=f"pkg_{suffix}_v{version}",
            title=title or checklist.title,
            watermark=watermark or f"仅供{checklist.title}使用 · {_dt.datetime.now(_dt.UTC).date().isoformat()}",
            entries=entries,
        )

    # ---------- 内部 ----------

    def _checklist(self, checklist_id: str) -> Checklist:
        checklist = self.store.get_checklist(checklist_id)
        if checklist is None:
            raise NotFoundError(checklist_id)
        return checklist
