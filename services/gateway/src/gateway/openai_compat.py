"""OpenAI 兼容入口：让 WeKnora 把网关当「自定义厂商」调用（WK06）。

WeKnora 模型配置支持 provider=generic + base_url + api_key，把 base_url 指向本模块
注册的 /v1 路由，其 LLM / embedding / rerank 调用即全部流经网关，从而保住四条红线：
L3 调用前脱敏、L4 只走 VPC 自部署、切换必带 switched_reason、按 tenant+dept+purpose+model 出账。

密级由密钥决定、不由请求体决定：一把密钥绑定 (tenant, dept, sensitivity)，
请求体里出现的 sensitivity / principal 一律忽略。

已知限制：OpenAI chat.completions 响应体无处承载 switched_reason，
故经响应头 X-Shilian-Switched-Reason（URL 编码）透出，并把实际通道写进
system_fingerprint。面向最终用户的问答入口必须是 shilian web（决策 D2）。
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import quote

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .router import NoEligibleModelError
from .clients.openai_compatible import MissingApiKeyError
from .schemas import InvokeRequest, Principal, Purpose, Sensitivity
from .service import GatewayService

logger = logging.getLogger("gateway.openai_compat")

# 切换原因 / 预算告警走响应头：协议体内没有合法位置可放（中文必须 URL 编码）
HEADER_SWITCHED_REASON = "X-Shilian-Switched-Reason"
HEADER_BUDGET_ALERT = "X-Shilian-Budget-Alert"


class BindingError(ValueError):
    """密钥未注册，或绑定的密级非法。"""


@dataclass(frozen=True)
class KeyBinding:
    """一把模型接入密钥绑定的身份与密级。"""

    tenant: str
    dept: str
    sensitivity: str


class KeyRegistry(Protocol):
    """按 API Key 反查绑定。生产实现应查密钥库，测试用内存注入。"""

    def lookup(self, api_key: str) -> KeyBinding: ...

    @property
    def enabled(self) -> bool: ...


@dataclass
class EnvKeyRegistry:
    """从环境变量 SHIM_KEY_<n>=<key>:<tenant>:<dept>:<sensitivity> 读取绑定。

    格式非法的条目直接忽略（宁可少放行，不静默放宽）。
    """

    bindings: dict[str, KeyBinding]

    @property
    def enabled(self) -> bool:
        return bool(self.bindings)

    def lookup(self, api_key: str) -> KeyBinding:
        binding = self.bindings.get(api_key)
        if binding is None:
            raise BindingError("未授权的模型接入密钥")
        return binding

    @classmethod
    def from_env(cls) -> EnvKeyRegistry:
        bindings: dict[str, KeyBinding] = {}
        for name, raw in os.environ.items():
            if not name.startswith("SHIM_KEY_") or not raw:
                continue
            parts = raw.split(":")
            if len(parts) != 4:
                logger.warning("忽略格式非法的 %s（应为 key:tenant:dept:sensitivity）", name)
                continue
            key, tenant, dept, sensitivity = (p.strip() for p in parts)
            if sensitivity not in {"L1", "L2", "L3", "L4"}:
                logger.warning("忽略非法密级：%s", sensitivity)
                continue
            if key:
                bindings[key] = KeyBinding(tenant, dept, sensitivity)
        return cls(bindings=bindings)


# ---------------- 请求模型 ----------------


class ChatMessage(BaseModel):
    role: str
    content: Any = None


class ChatCompletionsRequest(BaseModel):
    model: str
    messages: list[ChatMessage] = Field(default_factory=list)
    temperature: float | None = None
    max_tokens: int | None = None
    top_p: float | None = None
    stream: bool = False
    user: str | None = None


class EmbeddingsRequest(BaseModel):
    model: str
    input: Any = None
    user: str | None = None


class RerankRequest(BaseModel):
    model: str
    query: str = ""
    documents: list[Any] = Field(default_factory=list)
    top_n: int | None = None


def _resolve_binding(authorization: str | None, registry: KeyRegistry) -> KeyBinding:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="缺少模型接入密钥")
    try:
        return registry.lookup(authorization.split(" ", 1)[1].strip())
    except BindingError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc


def _invoke(service: GatewayService, binding: KeyBinding, purpose: Purpose,
            payload: dict[str, Any], user_id: str | None) -> Any:
    """把 OpenAI 请求翻译成带密级的 InvokeRequest。

    preferred_model 原样传网关：不合规时网关降级并给出 switched_reason，而不是放行。
    """
    request = InvokeRequest(
        purpose=purpose,
        sensitivity=Sensitivity(binding.sensitivity),
        preferred_model=payload.get("model"),
        principal=Principal(
            user_id=user_id or f"weknora-{binding.tenant}",
            dept=[binding.dept],
            tenant=binding.tenant,
        ),
        payload=payload,
    )
    try:
        return service.invoke(request)
    except NoEligibleModelError as exc:
        # 宁可报错也不降密级；detail 只给可读原因，不带文档内容
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except MissingApiKeyError:
        raise HTTPException(status_code=503, detail="网关侧模型密钥未配置") from None
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502,
                            detail=f"上游模型服务返回 {exc.response.status_code}") from exc
    except httpx.HTTPError:
        raise HTTPException(status_code=502, detail="上游模型服务不可达") from None


def _diagnostic_headers(result: Any) -> dict[str, str]:
    headers: dict[str, str] = {}
    reason = getattr(result, "switched_reason", None)
    if reason:
        headers[HEADER_SWITCHED_REASON] = quote(str(reason))
        logger.info("模型切换：%s（%s/%s）", reason, getattr(result, "model", "?"),
                    getattr(result, "channel", "?"))
    alert = getattr(result, "budget_alert", None)
    if alert:
        headers[HEADER_BUDGET_ALERT] = quote(str(alert))
        logger.info("预算告警：%s", alert)
    return headers


def register_openai_routes(app: FastAPI, service: GatewayService, registry: KeyRegistry) -> None:
    """把 OpenAI 兼容路由挂到既有 app 上（与 /v1/invoke 同进程共存）。"""

    @app.post("/v1/chat/completions")
    def chat_completions(
        request: ChatCompletionsRequest,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        if request.stream:
            # 明确失败，不静默降级成非流式：降级会让上游解析错乱，比 501 更难排查
            raise HTTPException(status_code=501, detail="流式输出尚未实现")
        binding = _resolve_binding(authorization, registry)
        messages = [{"role": m.role, "content": m.content} for m in request.messages]
        payload: dict[str, Any] = {"model": request.model, "messages": messages, "query": _last_user(messages)}
        for key in ("temperature", "max_tokens", "top_p"):
            value = getattr(request, key)
            if value is not None:
                payload[key] = value
        result = _invoke(service, binding, Purpose.generate, payload, request.user)
        text = (result.output or {}).get("text", "")
        usage = result.usage
        body = {
            "id": f"chatcmpl-{uuid.uuid4().hex[:24]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": result.model,
            "choices": [{"index": 0,
                         "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": usage.prompt_tokens,
                      "completion_tokens": usage.completion_tokens,
                      "total_tokens": usage.prompt_tokens + usage.completion_tokens},
            # 借既有字段透出实际通道：WeKnora 不会因此报错，排障时肉眼可辨
            "system_fingerprint": f"shilian_{result.channel.value}",
        }
        return JSONResponse(content=body, headers=_diagnostic_headers(result))

    @app.post("/v1/embeddings")
    def embeddings(
        request: EmbeddingsRequest,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        binding = _resolve_binding(authorization, registry)
        payload = {"model": request.model, "input": request.input if request.input is not None else []}
        result = _invoke(service, binding, Purpose.embed, payload, None)
        vectors = (result.output or {}).get("embeddings", [])
        body = {
            "object": "list",
            "model": result.model,
            "data": [{"object": "embedding", "index": i, "embedding": vec}
                     for i, vec in enumerate(vectors)],
            "usage": {"prompt_tokens": result.usage.prompt_tokens,
                      "total_tokens": result.usage.prompt_tokens},
        }
        return JSONResponse(content=body, headers=_diagnostic_headers(result))

    @app.post("/v1/rerank")
    def rerank(
        request: RerankRequest,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        binding = _resolve_binding(authorization, registry)
        payload = {"model": request.model, "query": request.query,
                   "documents": request.documents, "top_n": request.top_n}
        result = _invoke(service, binding, Purpose.rerank, payload, None)
        raw = (result.output or {}).get("results", [])
        results = [{"index": item.get("index", i),
                    "relevance_score": item.get("score", 0.0),
                    **({"document": item["document"]} if "document" in item else {})}
                   for i, item in enumerate(raw)]
        body = {"object": "list", "model": result.model, "results": results,
                "usage": {"total_tokens": result.usage.prompt_tokens}}
        return JSONResponse(content=body, headers=_diagnostic_headers(result))


def _last_user(messages: list[dict[str, Any]]) -> str:
    """契约 gateway.md 要求 generate 的 payload.query 必填：取最后一条 user 消息。"""
    for msg in reversed(messages):
        if msg.get("role") == "user":
            content = msg.get("content")
            return content if isinstance(content, str) else ""
    return ""


def create_openai_app(service: GatewayService | None = None,
                      registry: KeyRegistry | None = None) -> FastAPI:
    """独立 app（便于单测与本地联调）。生产走 register_openai_routes 挂到主 app。"""
    from .api import create_app

    app = create_app(service)
    _registry = registry or EnvKeyRegistry.from_env()
    if _registry.enabled:
        from .api import get_service

        register_openai_routes(app, get_service(), _registry)
    return app
