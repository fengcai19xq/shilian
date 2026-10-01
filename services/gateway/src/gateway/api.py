"""FastAPI 入口。对外只暴露契约里定义的统一调用接口。"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException

from .clients.openai_compatible import MissingApiKeyError
from .router import NoEligibleModelError
from .schemas import InvokeRequest, InvokeResponse
from .service import GatewayService

_service: GatewayService | None = None


def get_service() -> GatewayService:
    """默认单例；测试或部署时可用 app.dependency_overrides 注入自定义实例。"""
    global _service
    if _service is None:
        _service = GatewayService()
    return _service


def create_app(service: GatewayService | None = None) -> FastAPI:
    app = FastAPI(title="航锂智能体 —— 统一模型网关", version="0.1.0")

    if service is not None:
        app.dependency_overrides[get_service] = lambda: service

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/invoke", response_model=InvokeResponse)
    def invoke(
        request: InvokeRequest, svc: Annotated[GatewayService, Depends(get_service)]
    ) -> InvokeResponse:
        try:
            return svc.invoke(request)
        except NoEligibleModelError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except MissingApiKeyError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

    return app


app = create_app()
