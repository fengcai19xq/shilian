"""FastAPI 应用：`POST /retrieval/search`。

后端选择（WK08 / contracts/platform.md §2.5）：环境变量 RETRIEVE_DRIVER，
  - ragflow（默认，保持既有部署行为不变）
  - weknora
"""

from __future__ import annotations

import os
from typing import Annotated

from fastapi import Depends, FastAPI

from .adapters import RagflowBackend
from .audit import AuditSink, InMemoryAuditSink
from .backends import RetrievalBackend
from .models import SearchRequest, SearchResponse
from .service import RetrievalService

# 默认依赖：未注入时使用内存审计；检索后端必须由部署方注入
_default_audit_sink: AuditSink = InMemoryAuditSink()
_backend: RetrievalBackend | None = None


def set_dependencies(backend: RetrievalBackend, audit_sink: AuditSink | None = None) -> None:
    """由部署方（或测试）注入检索后端与审计写出。

    参数由 RagflowClient 放宽为 RetrievalBackend：旧的 RAGFlow 客户端
    经 RagflowBackend 包装后即可注入，测试 fake 直接实现 SPI 亦可注入。
    """
    global _backend, _default_audit_sink
    _backend = backend
    if audit_sink is not None:
        _default_audit_sink = audit_sink


def get_service() -> RetrievalService:
    if _backend is None:
        raise RuntimeError("尚未注入检索后端，请先调用 set_dependencies()")
    return RetrievalService(_backend, _default_audit_sink)


def create_app() -> FastAPI:
    app = FastAPI(title="retrieval-proxy", version="0.1.0")

    @app.post("/retrieval/search", response_model=SearchResponse)
    def search(
        request: SearchRequest,
        service: Annotated[RetrievalService, Depends(get_service)],
    ) -> SearchResponse:
        return service.search(request)

    return app


app = create_app()


def _build_backend_from_env() -> RetrievalBackend:
    """按 RETRIEVE_DRIVER 构造后端。缺省 ragflow，保持既有部署行为不变。"""
    driver = os.environ.get("RETRIEVE_DRIVER", "ragflow").strip().lower()
    if driver == "weknora":
        from .weknora import WeKnoraClient

        return WeKnoraClient.from_env()
    if driver == "ragflow":
        from .ragflow import HttpRagflowClient

        return RagflowBackend(HttpRagflowClient.from_env())
    raise RuntimeError(f"未知 RETRIEVE_DRIVER：{driver}（可选 ragflow / weknora）")


def create_app_from_env() -> FastAPI:
    """部署入口：`uvicorn retrieval_proxy.app:create_app_from_env --factory`。

    RAGFlow：RAGFLOW_BASE_URL / RAGFLOW_API_KEY
    WeKnora：WEKNORA_BASE_URL / WEKNORA_API_KEY / WEKNORA_TENANT_ID
    切换：RETRIEVE_DRIVER=ragflow|weknora
    """
    set_dependencies(_build_backend_from_env())
    return app
