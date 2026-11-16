"""MCP Server 端点：把 shilian 知识库对外发布给 Claude / Cursor 等外部工具（WK15）。

每个端点绑定：
- 独立 Bearer token（一端点一密钥，互不影响）
- kb_ids 白名单（只能检索授权的知识库）
- capability 最小化（默认只开放 search，可选 generate）
- 固定的权限主体（principal 由端点配置决定，不由调用方传入）

安全纪律（对齐 AGENTS.md 红线）：
1. 权限过滤在检索请求里完成——端点配置的 principal 四元组随检索请求下推
2. 越权文档不得出现在返回中（由 retrieval-proxy 保证）
3. 模型调用经 gateway——密级路由、脱敏、出账全部走既有链路
4. 默认关闭，通过环境变量 MCP_ENDPOINTS 配置后才挂载路由

端点配置格式（环境变量 MCP_ENDPOINTS，JSON 数组）：
[
  {
    "endpoint_id": "finance-search",
    "token": "mcp_sk_finance_xxx",
    "kb_scope": ["kb_finance"],
    "principal": {
      "user_id": "mcp_finance_search",
      "dept": ["融资部"],
      "max_sensitivity": "L2",
      "projects": []
    },
    "capabilities": ["search"]
  }
]
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

logger = logging.getLogger("gateway.mcp")


# ---------------- 端点配置 ----------------


@dataclass(frozen=True)
class McpEndpointConfig:
    """一个 MCP 端点的静态配置。token 明文比对，生产建议用足够长的随机串。"""

    endpoint_id: str
    token: str
    kb_scope: tuple[str, ...]
    principal: dict[str, Any]
    capabilities: tuple[str, ...] = ("search",)

    def has_capability(self, cap: str) -> bool:
        return cap in self.capabilities


@dataclass
class McpEndpointRegistry:
    """按 endpoint_id 与 token 查找端点配置。"""

    endpoints: dict[str, McpEndpointConfig] = field(default_factory=dict)
    _token_index: dict[str, McpEndpointConfig] = field(default_factory=dict, init=False, repr=False)

    def __post_init__(self) -> None:
        self._token_index = {}
        for ep in self.endpoints.values():
            self._token_index[ep.token] = ep

    @property
    def enabled(self) -> bool:
        return bool(self.endpoints)

    def by_endpoint(self, endpoint_id: str) -> McpEndpointConfig:
        ep = self.endpoints.get(endpoint_id)
        if ep is None:
            raise HTTPException(status_code=404, detail="端点不存在")
        return ep

    def authenticate(self, endpoint_id: str, token: str | None) -> McpEndpointConfig:
        ep = self.by_endpoint(endpoint_id)
        if not token:
            raise HTTPException(status_code=401, detail="缺少端点令牌")
        # 端点存在但 token 不匹配 → 403（不暴露端点是否存在）
        matched = self._token_index.get(token)
        if matched is None or matched.endpoint_id != endpoint_id:
            raise HTTPException(status_code=403, detail="端点令牌无效")
        return ep

    @classmethod
    def from_env(cls) -> McpEndpointRegistry:
        raw = os.environ.get("MCP_ENDPOINTS", "").strip()
        if not raw:
            return cls()
        try:
            items = json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.error("MCP_ENDPOINTS JSON 解析失败：%s", exc)
            return cls()
        if not isinstance(items, list):
            logger.error("MCP_ENDPOINTS 应为 JSON 数组")
            return cls()

        endpoints: dict[str, McpEndpointConfig] = {}
        for item in items:
            if not isinstance(item, dict):
                continue
            endpoint_id = item.get("endpoint_id", "").strip()
            token = item.get("token", "").strip()
            if not endpoint_id or not token:
                logger.warning("忽略缺少 endpoint_id 或 token 的 MCP 端点配置")
                continue
            kb_scope = tuple(item.get("kb_scope", []))
            if not kb_scope:
                logger.warning("忽略 kb_scope 为空的端点 %s（红线 1：必须有检索范围限制）", endpoint_id)
                continue
            principal = item.get("principal", {})
            if not principal.get("dept"):
                logger.warning("忽略 principal.dept 为空的端点 %s（红线 1：权限四元组不可缺）", endpoint_id)
                continue
            capabilities = tuple(item.get("capabilities", ["search"]))
            endpoints[endpoint_id] = McpEndpointConfig(
                endpoint_id=endpoint_id,
                token=token,
                kb_scope=kb_scope,
                principal=principal,
                capabilities=capabilities,
            )
        logger.info("MCP 端点已加载：%d 个", len(endpoints))
        return cls(endpoints=endpoints)


# ---------------- 请求 / 响应模型 ----------------


class McpSearchRequest(BaseModel):
    query: str
    top_k: int = Field(default=8, ge=1, le=20)
    score_threshold: float = Field(default=0.35, ge=0.0, le=1.0)
    mode: str = Field(default="full", pattern="^(probe|full)$")


class McpGenerateRequest(BaseModel):
    query: str
    preferred_model: str | None = None


# ---------------- 路由注册 ----------------


def register_mcp_routes(
    app: FastAPI,
    registry: McpEndpointRegistry,
    retrieval_base_url: str,
    gateway_service: Any | None = None,
    gateway_base_url: str | None = None,
) -> None:
    """把 MCP 路由挂到既有 app 上。默认关闭，registry 为空时不挂载。

    retrieval_base_url: retrieval-proxy 地址，如 http://retrieval-proxy:8000
    gateway_base_url:   gateway 地址（仅 generate capability 时需要），如 http://localhost:8000
    gateway_service:    如果同进程可直接调用 GatewayService，避免 HTTP 往返
    """

    if not registry.enabled:
        logger.info("MCP 端点未配置，路由不挂载")
        return

    @app.get("/mcp/{endpoint_id}/tools")
    def list_tools(
        endpoint_id: str,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        """列出端点可用工具——capability 最小化。"""
        token = _extract_bearer(authorization)
        ep = registry.authenticate(endpoint_id, token)
        tools: list[dict[str, Any]] = [
            {
                "name": "search",
                "description": "在授权知识库中检索文档片段",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "检索查询"},
                        "top_k": {"type": "integer", "default": 8, "minimum": 1, "maximum": 20},
                        "mode": {"type": "string", "enum": ["probe", "full"], "default": "full"},
                    },
                    "required": ["query"],
                },
            }
        ]
        if ep.has_capability("generate"):
            tools.append({
                "name": "generate",
                "description": "基于知识库内容生成回答（经网关密级路由）",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "问题"},
                        "preferred_model": {"type": "string"},
                    },
                    "required": ["query"],
                },
            })
        return JSONResponse(content={"tools": tools})

    @app.post("/mcp/{endpoint_id}/search")
    def search(
        endpoint_id: str,
        request: McpSearchRequest,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        token = _extract_bearer(authorization)
        ep = registry.authenticate(endpoint_id, token)
        if not ep.has_capability("search"):
            raise HTTPException(status_code=403, detail="该端点未开放 search 能力")

        body = {
            "query": request.query,
            "principal": ep.principal,
            "kb_scope": list(ep.kb_scope),
            "top_k": request.top_k,
            "score_threshold": request.score_threshold,
            "mode": request.mode,
        }
        try:
            resp = httpx.post(
                f"{retrieval_base_url}/retrieval/search",
                json=body,
                timeout=30.0,
            )
            resp.raise_for_status()
        except httpx.HTTPStatusError as exc:
            logger.warning("MCP 检索上游返回 %s（endpoint=%s）", exc.response.status_code, endpoint_id)
            raise HTTPException(status_code=502, detail="检索服务不可用") from exc
        except httpx.HTTPError:
            raise HTTPException(status_code=502, detail="检索服务不可达") from None

        return JSONResponse(content=resp.json())

    @app.post("/mcp/{endpoint_id}/generate")
    def generate(
        endpoint_id: str,
        request: McpGenerateRequest,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        token = _extract_bearer(authorization)
        ep = registry.authenticate(endpoint_id, token)
        if not ep.has_capability("generate"):
            raise HTTPException(status_code=403, detail="该端点未开放 generate 能力")

        sensitivity = ep.principal.get("max_sensitivity", "L2")
        invoke_body = {
            "purpose": "generate",
            "sensitivity": sensitivity,
            "preferred_model": request.preferred_model,
            "principal": {
                "user_id": ep.principal.get("user_id", f"mcp-{endpoint_id}"),
                "dept": ep.principal.get("dept", []),
                "max_sensitivity": sensitivity,
                "tenant": ep.principal.get("tenant", ""),
            },
            "payload": {"query": request.query},
        }

        if gateway_service is not None:
            # 同进程直接调用，避免 HTTP 往返
            try:
                from .schemas import InvokeRequest
                result = gateway_service.invoke(InvokeRequest(**invoke_body))
                return JSONResponse(content=result.model_dump())
            except Exception as exc:
                logger.warning("MCP 生成失败（endpoint=%s）：%s", endpoint_id, exc)
                raise HTTPException(status_code=502, detail="模型服务不可用") from exc

        # 走 HTTP 调用 gateway
        if not gateway_base_url:
            raise HTTPException(status_code=500, detail="generate 能力未配置网关地址")
        try:
            resp = httpx.post(
                f"{gateway_base_url}/v1/invoke",
                json=invoke_body,
                timeout=60.0,
            )
            resp.raise_for_status()
        except httpx.HTTPStatusError as exc:
            logger.warning("MCP 生成上游返回 %s（endpoint=%s）", exc.response.status_code, endpoint_id)
            raise HTTPException(status_code=502, detail="模型服务不可用") from exc
        except httpx.HTTPError:
            raise HTTPException(status_code=502, detail="模型服务不可达") from None

        return JSONResponse(content=resp.json())

    logger.info("MCP 路由已挂载：%d 个端点", len(registry.endpoints))


def _extract_bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    if authorization.lower().startswith("bearer "):
        return authorization.split(" ", 1)[1].strip()
    return authorization.strip()
