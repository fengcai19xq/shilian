"""BFF：浏览器唯一入口。

职责（只做“收口”，不做业务）：

1. 用会话（Authorization / Cookie）向 wecom-sync 换 principal；
2. 丢弃客户端传来的 `principal` / `X-User-Id`，只把服务端解析出的主体注入下游；
3. 代理 retrieval / decide / gateway / ingest，并转发诊断响应头；
4. 企业问答生成时把 `sensitivity` 抬到本次检索上下文的最高密级，防止客户端降级绕过脱敏；
5. 下游错误只回状态码与服务名，不回显响应体（越权文档名不得外泄）。

契约来源：contracts/retrieval.md、gateway.md、decide.md、platform.md。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse

from .config import Settings
from .context import SensitivityContextStore, max_sensitivity, sign_context, verify_context
from .models import Principal
from .principal import IdentityUnavailable, PrincipalResolver, Unauthenticated

DIAGNOSTIC_HEADERS = ("X-Shilian-Switched-Reason", "X-Shilian-Budget-Alert")
CONTEXT_COOKIE = "shilian_ctx"


def _error(service: str, status: int) -> JSONResponse:
    """下游失败统一收口：只给状态码与服务名，不透传响应体。"""
    if status >= 500:
        return JSONResponse({"detail": f"{service} 服务暂不可用"}, status_code=502)
    return JSONResponse({"detail": f"{service} 拒绝了请求"}, status_code=status)


async def _json_body(request: Request) -> dict[str, Any]:
    try:
        body = await request.json()
    except ValueError:
        raise _bad_request("请求体必须是 JSON")
    if not isinstance(body, dict):
        raise _bad_request("请求体必须是 JSON 对象")
    return body


class _bad_request(Exception):
    def __init__(self, detail: str) -> None:
        self.detail = detail


def create_app(
    settings: Settings | None = None,
    http_client: httpx.AsyncClient | None = None,
) -> FastAPI:
    effective = settings or Settings.from_env()
    owns_client = http_client is None
    client = http_client or httpx.AsyncClient(timeout=effective.timeout_seconds)
    resolver = PrincipalResolver(effective, client)
    context = SensitivityContextStore(effective.context_ttl_seconds)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        if owns_client:
            await client.aclose()

    app = FastAPI(title="shilian BFF", version="0.1.0", lifespan=lifespan)

    async def current_principal(request: Request) -> Principal:
        try:
            return await resolver.resolve(
                request.headers.get("authorization"),
                request.headers.get("x-user-id"),
                request.headers.get("cookie"),
            )
        except Unauthenticated:
            raise _unauthorized()
        except IdentityUnavailable:
            raise _identity_unavailable()

    async def current_admin(principal: Principal = Depends(current_principal)) -> Principal:
        if not (set(principal.roles) & set(effective.admin_roles)):
            from fastapi import HTTPException

            raise HTTPException(status_code=403, detail="需要管理员权限")
        return principal

    async def _proxy_json(
        service: str,
        url: str,
        body: dict[str, Any],
        *,
        extra_headers: dict[str, str] | None = None,
        diagnostic: bool = False,
    ) -> JSONResponse:
        headers = {"Content-Type": "application/json"}
        if effective.internal_token:
            headers["X-Internal-Token"] = effective.internal_token
        if extra_headers:
            headers.update(extra_headers)
        try:
            resp = await client.post(url, json=body, headers=headers)
        except httpx.HTTPError:
            return JSONResponse({"detail": f"{service} 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error(service, resp.status_code)
        try:
            data = resp.json()
        except ValueError:
            return JSONResponse({"detail": f"{service} 返回了非 JSON 响应"}, status_code=502)
        response_headers = (
            {name: resp.headers[name] for name in DIAGNOSTIC_HEADERS if name in resp.headers}
            if diagnostic
            else None
        )
        return JSONResponse(content=data, headers=response_headers, status_code=resp.status_code)

    @app.exception_handler(_bad_request)
    async def bad_request_handler(_: Request, exc: _bad_request) -> JSONResponse:
        return JSONResponse({"detail": exc.detail}, status_code=400)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok", "mode": "dev" if effective.dev_mode else "session"}

    # ---------------- 会话身份 ----------------

    @app.get("/identity/me")
    async def me(principal: Principal = Depends(current_principal)) -> dict[str, Any]:
        return principal.as_downstream()

    # ---------------- 检索 / 路由 ----------------

    @app.post("/retrieval/search")
    async def retrieval_search(
        request: Request,
        principal: Principal = Depends(current_principal),
    ) -> JSONResponse:
        body = await _json_body(request)
        body.pop("principal", None)  # 客户端传什么都不算
        body["principal"] = principal.as_downstream()
        if effective.kb_scope:
            body["kb_scope"] = list(effective.kb_scope)

        headers = {"Content-Type": "application/json"}
        if effective.internal_token:
            headers["X-Internal-Token"] = effective.internal_token
        try:
            resp = await client.post(
                f"{effective.retrieval_url}/retrieval/search", json=body, headers=headers
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "retrieval 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error("retrieval", resp.status_code)
        try:
            data = resp.json()
        except ValueError:
            return JSONResponse({"detail": "retrieval 返回了非 JSON 响应"}, status_code=502)
        hits = data.get("hits", []) if isinstance(data, dict) else []
        context.record_hits(principal.user_id, hits)
        headers = {}
        if effective.context_secret:
            level = max_sensitivity(*(str(h.get("sensitivity", "")) for h in hits if isinstance(h, dict)))
            cookie = _context_cookie(principal.user_id, level, effective)
            if cookie:
                headers["set-cookie"] = cookie
        return JSONResponse(content=data, headers=headers)

    @app.post("/decide/route")
    async def decide_route(
        request: Request,
        principal: Principal = Depends(current_principal),
    ) -> JSONResponse:
        body = await _json_body(request)
        body.pop("principal", None)
        body["principal"] = principal.as_downstream()
        if effective.kb_scope:
            body["kb_scope"] = list(effective.kb_scope)
        return await _proxy_json("decide", f"{effective.decide_url}/decide/route", body)

    # ---------------- 模型网关 ----------------

    @app.post("/gateway/v1/invoke")
    async def gateway_invoke(
        request: Request,
        principal: Principal = Depends(current_principal),
    ) -> JSONResponse:
        body = await _json_body(request)
        body.pop("principal", None)
        payload = body.get("payload")
        messages = payload.get("messages") if isinstance(payload, dict) else None

        if messages:
            # 企业知识问答：必须已有服务端记录的检索上下文，且密级只能抬不能降。
            # 优先用无状态签名 Cookie（多副本可用），同实例内存作为快速路径。
            floor = context.floor_for(principal.user_id)
            signed = request.cookies.get(CONTEXT_COOKIE)
            if signed and effective.context_secret:
                verified = verify_context(signed, effective.context_secret)
                if verified and verified[0] == principal.user_id:
                    floor = max_sensitivity(floor, verified[1]) if floor else verified[1]
            if floor is None:
                return JSONResponse(
                    {"detail": "缺少企业检索上下文，请先检索后再生成"}, status_code=409
                )
            body["sensitivity"] = max_sensitivity(str(body.get("sensitivity", "L1")), floor)
            body["principal"] = principal.as_downstream()
            extra_headers: dict[str, str] = {}
        else:
            # 通用问答：不带任何公司数据，也不把权限主体传给模型网关
            body["sensitivity"] = "L1"
            extra_headers = {"X-User-Id": principal.user_id}

        return await _proxy_json(
            "gateway",
            f"{effective.gateway_url}/v1/invoke",
            body,
            extra_headers=extra_headers,
            diagnostic=True,
        )

    # ---------------- 入库（管理面/后续上传 UI） ----------------

    def _ingest_headers(principal: Principal, request: Request) -> dict[str, str]:
        headers = {
            "X-User-Id": principal.user_id,
            "X-Tenant-Id": principal.tenant,
        }
        content_type = request.headers.get("content-type")
        if content_type:
            headers["Content-Type"] = content_type
        if effective.internal_token:
            headers["X-Internal-Token"] = effective.internal_token
        return headers

    @app.post("/ingest/documents")
    async def ingest_upload(
        request: Request,
        principal: Principal = Depends(current_admin),
    ):
        content = await request.body()
        suffix = f"?{request.url.query}" if request.url.query else ""
        try:
            resp = await client.post(
                f"{effective.ingest_url}/ingest/documents{suffix}",
                content=content,
                headers=_ingest_headers(principal, request),
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "ingest 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error("ingest", resp.status_code)
        return JSONResponse(content=resp.json(), status_code=resp.status_code)

    @app.get("/ingest/documents")
    async def ingest_list(
        request: Request,
        principal: Principal = Depends(current_admin),
    ):
        suffix = f"?{request.url.query}" if request.url.query else ""
        try:
            resp = await client.get(
                f"{effective.ingest_url}/ingest/documents{suffix}",
                headers={"X-User-Id": principal.user_id, "X-Tenant-Id": principal.tenant},
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "ingest 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error("ingest", resp.status_code)
        return JSONResponse(content=resp.json(), status_code=resp.status_code)

    @app.get("/ingest/documents/{doc_id}")
    async def ingest_get(
        doc_id: str,
        principal: Principal = Depends(current_admin),
    ):
        try:
            resp = await client.get(
                f"{effective.ingest_url}/ingest/documents/{doc_id}",
                headers={"X-User-Id": principal.user_id, "X-Tenant-Id": principal.tenant},
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "ingest 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error("ingest", resp.status_code)
        return JSONResponse(content=resp.json(), status_code=resp.status_code)



    async def _proxy_get(service: str, url: str, headers: dict[str, str]) -> JSONResponse:
        try:
            resp = await client.get(url, headers=headers)
        except httpx.HTTPError:
            return JSONResponse({"detail": f"{service} 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error(service, resp.status_code)
        try:
            data = resp.json()
        except ValueError:
            return JSONResponse({"detail": f"{service} 返回了非 JSON 响应"}, status_code=502)
        return JSONResponse(content=data, status_code=resp.status_code)

    # ---------------- 清单匹配 / 成册 ----------------

    def _matcher_headers(principal: Principal, request: Request | None = None) -> dict[str, str]:
        headers = {"X-User-Id": principal.user_id}
        if request is not None:
            content_type = request.headers.get("content-type")
            if content_type:
                headers["Content-Type"] = content_type
        if effective.internal_token:
            headers["X-Internal-Token"] = effective.internal_token
        return headers

    @app.post("/matcher/checklists")
    async def matcher_upload(
        request: Request,
        principal: Principal = Depends(current_principal),
    ):
        content = await request.body()
        suffix = f"?{request.url.query}" if request.url.query else ""
        try:
            resp = await client.post(
                f"{effective.matcher_url}/matcher/checklists{suffix}",
                content=content,
                headers=_matcher_headers(principal, request),
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "matcher 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error("matcher", resp.status_code)
        return JSONResponse(content=resp.json(), status_code=resp.status_code)

    @app.get("/matcher/checklists/{checklist_id}")
    async def matcher_get_checklist(
        checklist_id: str,
        principal: Principal = Depends(current_principal),
    ):
        return await _proxy_get(
            "matcher", f"{effective.matcher_url}/matcher/checklists/{checklist_id}",
            _matcher_headers(principal),
        )

    @app.post("/matcher/checklists/{checklist_id}/run")
    async def matcher_run(
        checklist_id: str,
        principal: Principal = Depends(current_principal),
    ):
        # 前端不能自带 principal；BFF 用会话主体覆盖
        body = {"principal": principal.as_downstream()}
        return await _proxy_json(
            "matcher", f"{effective.matcher_url}/matcher/checklists/{checklist_id}/run", body,
            extra_headers=_matcher_headers(principal),
        )

    @app.get("/matcher/tasks/{task_id}")
    async def matcher_task(
        task_id: str,
        principal: Principal = Depends(current_principal),
    ):
        return await _proxy_get(
            "matcher", f"{effective.matcher_url}/matcher/tasks/{task_id}",
            _matcher_headers(principal),
        )

    @app.post("/matcher/items/{item_id}/confirm")
    async def matcher_confirm(
        item_id: str,
        request: Request,
        principal: Principal = Depends(current_principal),
    ):
        body = await _json_body(request)
        return await _proxy_json(
            "matcher", f"{effective.matcher_url}/matcher/items/{item_id}/confirm", body,
            extra_headers=_matcher_headers(principal),
        )

    @app.get("/matcher/checklists/{checklist_id}/manifest")
    async def matcher_manifest(
        checklist_id: str,
        principal: Principal = Depends(current_principal),
    ):
        return await _proxy_get(
            "matcher", f"{effective.matcher_url}/matcher/checklists/{checklist_id}/manifest",
            _matcher_headers(principal),
        )

    @app.post("/matcher/checklists/{checklist_id}/package")
    async def matcher_package(
        checklist_id: str,
        principal: Principal = Depends(current_principal),
    ):
        """只允许“服务器生成的 manifest 进 packager”，不接受浏览器自造 manifest。"""
        try:
            manifest_resp = await client.get(
                f"{effective.matcher_url}/matcher/checklists/{checklist_id}/manifest",
                headers=_matcher_headers(principal),
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "matcher 服务暂不可用"}, status_code=502)
        if manifest_resp.status_code >= 400:
            return _error("matcher", manifest_resp.status_code)
        try:
            manifest = manifest_resp.json()
        except ValueError:
            return JSONResponse({"detail": "manifest 不是 JSON"}, status_code=502)
        headers = {"Content-Type": "application/json"}
        if effective.internal_token:
            headers["X-Internal-Token"] = effective.internal_token
        try:
            resp = await client.post(
                f"{effective.packager_url}/v1/packages", json=manifest, headers=headers
            )
        except httpx.HTTPError:
            return JSONResponse({"detail": "packager 服务暂不可用"}, status_code=502)
        if resp.status_code >= 400:
            return _error("packager", resp.status_code)
        try:
            data = resp.json()
        except ValueError:
            return JSONResponse({"detail": "packager 返回了非 JSON 响应"}, status_code=502)
        return JSONResponse(content=data, status_code=resp.status_code)

    return app


def _context_cookie(user_id: str, level: str, settings: Settings) -> str:
    value = sign_context(user_id, level, settings.context_secret, settings.context_ttl_seconds)
    parts = [
        f"{CONTEXT_COOKIE}={value}",
        "Path=/",
        "HttpOnly",
        "SameSite=Lax",
        f"Max-Age={settings.context_ttl_seconds}",
    ]
    if settings.context_cookie_secure:
        parts.append("Secure")
    return "; ".join(parts)


def _unauthorized() -> Exception:
    from fastapi import HTTPException

    return HTTPException(status_code=401, detail="未登录或会话已过期")


def _identity_unavailable() -> Exception:
    from fastapi import HTTPException

    return HTTPException(status_code=503, detail="身份服务暂不可用")


app = create_app()
