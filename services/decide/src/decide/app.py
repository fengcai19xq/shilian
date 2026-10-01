"""FastAPI 应用。对外只暴露 POST /decide/route。"""

from __future__ import annotations

from fastapi import FastAPI

from decide.clients import (
    GatewayClient,
    HttpGatewayClient,
    HttpRetrievalClient,
    RetrievalClient,
)
from decide.config import Settings
from decide.deciders import Decider, build_decider
from decide.models import RouteRequest, RouteResponse
from decide.routing import RoutingService


def build_service(
    settings: Settings | None = None,
    retrieval: RetrievalClient | None = None,
    gateway: GatewayClient | None = None,
    decider: Decider | None = None,
) -> RoutingService:
    """依赖可注入：测试传 fake，运行时默认走 HTTP 客户端。"""
    settings = settings or Settings.from_env()
    retrieval = retrieval or HttpRetrievalClient(
        settings.retrieval_base_url, settings.http_timeout_seconds
    )
    gateway = gateway or HttpGatewayClient(settings.gateway_base_url, settings.http_timeout_seconds)
    decider = decider or build_decider(settings.decider_backend, gateway)
    return RoutingService(settings=settings, retrieval=retrieval, decider=decider)


def create_app(service: RoutingService | None = None) -> FastAPI:
    app = FastAPI(title="shilian decide", version="0.1.0")
    app.state.service = service

    def _service() -> RoutingService:
        if app.state.service is None:
            app.state.service = build_service()
        return app.state.service

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/decide/route", response_model=RouteResponse)
    def route(request: RouteRequest) -> RouteResponse:
        return _service().route(request)

    return app


app = create_app()
