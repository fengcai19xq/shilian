"""FastAPI 应用：`POST /retrieval/search`。"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, FastAPI

from .audit import AuditSink, InMemoryAuditSink
from .models import SearchRequest, SearchResponse
from .ragflow import RagflowClient
from .service import RetrievalService

# 默认依赖：未注入时使用内存审计；RAGFlow 客户端必须由部署方注入
_default_audit_sink: AuditSink = InMemoryAuditSink()
_client: RagflowClient | None = None


def set_dependencies(client: RagflowClient, audit_sink: AuditSink | None = None) -> None:
    """由部署方（或测试）注入 RAGFlow 客户端与审计写出。"""
    global _client, _default_audit_sink
    _client = client
    if audit_sink is not None:
        _default_audit_sink = audit_sink


def get_service() -> RetrievalService:
    if _client is None:
        raise RuntimeError("尚未注入 RAGFlow 客户端，请先调用 set_dependencies()")
    return RetrievalService(_client, _default_audit_sink)


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
