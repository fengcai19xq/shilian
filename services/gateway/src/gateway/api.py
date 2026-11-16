"""FastAPI 入口。对外只暴露契约里定义的统一调用接口。"""

from __future__ import annotations

from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException

from .clients.openai_compatible import MissingApiKeyError
from .mcp_server import McpEndpointRegistry, register_mcp_routes
from .openai_compat import EnvKeyRegistry, register_openai_routes
from .router import NoEligibleModelError
from .schemas import InvokeRequest, InvokeResponse, Principal
from .service import GatewayService

_service: GatewayService | None = None


def get_service() -> GatewayService:
    """默认单例；测试或部署时可用 app.dependency_overrides 注入自定义实例。"""
    global _service
    if _service is None:
        _service = GatewayService()
    return _service


ANONYMOUS_USER = "anonymous"


def create_app(service: GatewayService | None = None) -> FastAPI:
    app = FastAPI(title="航锂智能体 —— 统一模型网关", version="0.1.0")

    effective = service
    if service is not None:
        app.dependency_overrides[get_service] = lambda: service

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/invoke", response_model=InvokeResponse)
    def invoke(
        request: InvokeRequest,
        svc: Annotated[GatewayService, Depends(get_service)],
        x_user_id: Annotated[str | None, Header()] = None,
    ) -> InvokeResponse:
        if request.principal is None:
            request.principal = Principal(user_id=x_user_id or ANONYMOUS_USER)
        try:
            return svc.invoke(request)
        except NoEligibleModelError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except MissingApiKeyError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        except httpx.HTTPStatusError as exc:
            raise HTTPException(
                status_code=502,
                detail=f"上游模型服务返回 {exc.response.status_code}，请检查账户额度或密钥",
            ) from exc
        except httpx.HTTPError as exc:
            raise HTTPException(status_code=502, detail="上游模型服务不可达") from exc

    # OpenAI 兼容入口（WK06）：WeKnora 等外部框架按「自定义厂商」调这些路由，
    # 全部流经上面的密级路由 / 脱敏 / 出账逻辑。密钥注册表为空时不挂载。
    registry = EnvKeyRegistry.from_env()
    if registry.enabled:
        register_openai_routes(app, effective or get_service(), registry)

    # MCP Server 端点（WK15）：把 shilian 知识库对外发布给 Claude / Cursor 等工具。
    # 默认关闭，通过 MCP_ENDPOINTS 环境变量配置后才挂载。权限过滤由 retrieval-proxy 保证。
    mcp_registry = McpEndpointRegistry.from_env()
    if mcp_registry.enabled:
        import os as _os
        _retrieval_url = _os.environ.get("MCP_RETRIEVAL_URL", "http://retrieval-proxy:8000")
        register_mcp_routes(
            app, mcp_registry,
            retrieval_base_url=_retrieval_url,
            gateway_service=effective or get_service(),
        )

    return app


app = create_app()
