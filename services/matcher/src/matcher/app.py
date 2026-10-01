"""FastAPI 接口层（contracts/matching.md「接口」一节）。

操作人取自网关注入的 X-User-Id 请求头，不由前端自报。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import BackgroundTasks, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

from .models import Checklist, ItemResult, Manifest, MatchTask
from .ports import NullCandidateProvider, RejectAllDecideProvider
from .service import MatcherService, NotFoundError, StateError


class RunResponse(BaseModel):
    task_id: str
    checklist_id: str
    state: str


class TaskResponse(BaseModel):
    task: MatchTask
    progress: float


class ConfirmRequest(BaseModel):
    doc_ids: list[str] = Field(default_factory=list)
    note: str = ""


def create_app(service: MatcherService) -> FastAPI:
    app = FastAPI(title="matcher", version="0.1.0")
    app.state.service = service

    @app.post("/matcher/checklists", response_model=Checklist, status_code=201)
    async def upload_checklist(
        file: Annotated[UploadFile | None, File()] = None,
        text: Annotated[str | None, Form()] = None,
        title: Annotated[str, Form()] = "",
        x_user_id: Annotated[str | None, Header()] = None,
    ) -> Checklist:
        if file is not None:
            content: bytes | str = await file.read()
            filename = file.filename or ""
        elif text:
            content, filename = text, ""
        else:
            raise HTTPException(status_code=400, detail="需要上传 file 或提供 text")
        try:
            return service.upload(content, filename=filename, title=title,
                                  operator=x_user_id or "system")
        except Exception as exc:  # noqa: BLE001 - 解析失败统一回 422
            raise HTTPException(status_code=422, detail=f"清单解析失败：{type(exc).__name__}")

    @app.post("/matcher/checklists/{checklist_id}/run", response_model=RunResponse,
              status_code=202)
    def run_match(checklist_id: str, background: BackgroundTasks) -> RunResponse:
        try:
            task = service.create_task(checklist_id)
        except NotFoundError:
            raise HTTPException(status_code=404, detail="清单不存在")
        background.add_task(service.run_task, task.task_id)
        return RunResponse(task_id=task.task_id, checklist_id=checklist_id, state=task.state)

    @app.get("/matcher/tasks/{task_id}", response_model=TaskResponse)
    def get_task(task_id: str) -> TaskResponse:
        try:
            task = service.get_task(task_id)
        except NotFoundError:
            raise HTTPException(status_code=404, detail="任务不存在")
        return TaskResponse(task=task, progress=task.progress)

    @app.post("/matcher/items/{item_id}/confirm", response_model=ItemResult)
    def confirm_item(
        item_id: str,
        body: ConfirmRequest,
        x_user_id: Annotated[str | None, Header()] = None,
    ) -> ItemResult:
        if not x_user_id:
            raise HTTPException(status_code=401, detail="缺少操作人")
        try:
            return service.confirm(item_id, body.doc_ids, operator=x_user_id, note=body.note)
        except NotFoundError:
            raise HTTPException(status_code=404, detail="清单项不存在")
        except StateError as exc:
            raise HTTPException(status_code=409, detail=str(exc))

    @app.get("/matcher/checklists/{checklist_id}/manifest", response_model=Manifest)
    def export_manifest(checklist_id: str, title: str | None = None,
                        watermark: str = "") -> Manifest:
        try:
            return service.manifest(checklist_id, title=title, watermark=watermark)
        except NotFoundError:
            raise HTTPException(status_code=404, detail="清单不存在")

    return app


def build_default_service() -> MatcherService:
    """未接入召回与 decide 时的安全默认：全部落 missing，不会产生假阳性。"""
    return MatcherService(candidates=NullCandidateProvider(), decide=RejectAllDecideProvider())


app = create_app(build_default_service())
