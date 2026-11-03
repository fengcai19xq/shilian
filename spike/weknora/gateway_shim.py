"""OpenAI 兼容 shim：让 WeKnora 把 shilian gateway 当作一个"自定义厂商"调用。

## 集成点

WeKnora 模型配置支持 `provider=generic`（自定义 OpenAI 兼容接口）+ `base_url` + `api_key`，
且 `base_url` 会经过 SSRF 校验（docs/03-features/06-models）。把 `base_url` 指向本 shim，
WeKnora 的 LLM / embedding 调用就全部流经 shilian gateway，从而保住四条红线：

- L3 调用前脱敏（gateway 的 mask_required 分支）
- L4 只落 vpc_self_hosted 通道
- 通道/模型切换必带 switched_reason
- 按 dept + purpose + model 出账

WeKnora 侧无需改一行 Go 代码。

## 密级如何识别

WeKnora 的模型配置是 KB 级的（每个知识库各自选模型）。约定：
**一把 shim API Key 绑定一个 (tenant, dept, sensitivity)**，
shim 收到请求后用 key 反查这三元组，构造 gateway 的 InvokeRequest。
于是"密级"这个 WeKnora 没有的概念，被编码进了它的模型配置维度——
KB 级模型配置与密级模型天然同构，这是整个融合方案最省力的对齐点。

## 已知限制（必须在评审中标注）

红线 4 要求"模型自动切换通道时必须返回 switched_reason，前端显式提示"。
OpenAI chat.completions 响应体**没有承载该字段的位置**，WeKnora 作为标准客户端
会忽略未知字段，因此：

- 走 shilian 自有 web 工作台时：shim 把切换事件写审计，web 侧显式提示（红线 4 满足）
- 走 WeKnora 原生 UI 时：切换原因无法在对话界面展示（红线 4 **不满足**）

结论：**面向最终用户的问答入口必须是 shilian web，WeKnora 原生 UI 只用于管理面**。
这不是实现偷懒，是 OpenAI 协议的信息容量限制。shim 仍然把 switched_reason
写进响应头与审计，供 shilian web 与排障使用。
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
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

logger = logging.getLogger("weknora_shim")

# 切换原因响应头。WeKnora 不读它，但 shilian web / 排障工具可以。
HEADER_SWITCHED_REASON = "X-Shilian-Switched-Reason"
HEADER_BUDGET_ALERT = "X-Shilian-Budget-Alert"


class KeyNotAllowedError(RuntimeError):
    """请求携带的 key 不在注册表内，或缺少必要绑定信息。"""


@dataclass(frozen=True)
class KeyBinding:
    """一把 shim key 绑定的身份与密级。"""

    tenant: str
    dept: str
    sensitivity: str  # L1..L4
    # purpose 固定由端点决定（chat->generate, embeddings->embed），不放在 key 上


class KeyRegistry(Protocol):
    """按 API Key 反查绑定。生产实现应查 gateway 的密钥库，spike 用内存/环境变量。"""

    def lookup(self, api_key: str) -> KeyBinding: ...


@dataclass
class EnvKeyRegistry:
    """从环境变量读 `SHIM_KEY_<N>=<key>:<tenant>:<dept>:<sensitivity>`。"""

    bindings: dict[str, KeyBinding]

    def lookup(self, api_key: str) -> KeyBinding:
        binding = self.bindings.get(api_key)
        if binding is None:
            raise KeyNotAllowedError("未授权的模型接入密钥")
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
            key, tenant, dept, sensitivity = parts
            bindings[key] = KeyBinding(tenant, dept, sensitivity)
        return cls(bindings=bindings)


class GatewayClient(Protocol):
    """调用 gateway `/v1/invoke`。测试可注入假实现，spike 因此能离线跑通。"""

    def invoke(self, body: dict[str, Any]) -> dict[str, Any]: ...


@dataclass
class HttpGatewayClient:
    base_url: str
    timeout: float = 60.0

    def invoke(self, body: dict[str, Any]) -> dict[str, Any]:
        url = self.base_url.rstrip("/") + "/v1/invoke"
        resp = httpx.post(url, json=body, timeout=self.timeout)
        if resp.status_code >= 400:
            # 不透出上游响应体：gateway 422（无合规模型）/ 502（上游故障）都只给状态码，
            # 避免把模型侧错误信息里的内容片段带回给 WeKnora。
            raise HTTPException(status_code=502, detail=f"模型网关返回 {resp.status_code}")
        return resp.json()


# ---- 请求/响应模型 ----


class ChatMessage(BaseModel):
    role: str
    content: Any = None


class ChatCompletionRequest(BaseModel):
    model: str
    messages: list[ChatMessage] = Field(default_factory=list)
    temperature: float | None = None
    max_tokens: int | None = None
    top_p: float | None = None
    stream: bool = False
    user: str | None = None


class EmbeddingRequest(BaseModel):
    model: str
    input: Any = None
    stream: bool = False


def create_app(
    registry: KeyRegistry | None = None,
    gateway: GatewayClient | None = None,
) -> FastAPI:
    app = FastAPI(title="shilian ↔ WeKnora gateway shim (spike)", version="0.1.0")
    _registry = registry or EnvKeyRegistry.from_env()
    _gateway: GatewayClient = gateway or HttpGatewayClient(
        os.environ.get("SHIM_GATEWAY_BASE_URL", "http://gateway:8000")
    )

    def resolve_binding(authorization: str | None) -> KeyBinding:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(status_code=401, detail="缺少模型接入密钥")
        try:
            return _registry.lookup(authorization.split(" ", 1)[1].strip())
        except KeyNotAllowedError as exc:
            raise HTTPException(status_code=403, detail=str(exc)) from exc

    def invoke_gateway(
        binding: KeyBinding, purpose: str, payload: dict[str, Any], user_id: str | None
    ) -> dict[str, Any]:
        body = {
            "purpose": purpose,
            "sensitivity": binding.sensitivity,
            # WeKnora 侧看不到 shilian 的 preferred_model 概念；传 model 名让 gateway
            # 校验合规性——不合规时它会降级并给出 switched_reason，而不是直接放行。
            "preferred_model": payload.get("model"),
            "principal": {
                "user_id": user_id or f"weknora-{binding.tenant}",
                "dept": [binding.dept],
            },
            "payload": payload,
        }
        return _gateway.invoke(body)

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/chat/completions")
    def chat_completions(
        request: ChatCompletionRequest,
        raw: Request,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        if request.stream:
            # WeKnora 对话默认走流式。spike 阶段先不支持，用 compat JSON
            # {"supports_usage_in_streaming": false} 之外还需上游不发 stream；
            # 未实现前明确 501，避免静默降级成非流式导致 WeKnora 解析异常。
            raise HTTPException(status_code=501, detail="流式输出尚未实现（见 spike 报告 P1 待办）")
        binding = resolve_binding(authorization)
        payload = request.model_dump(exclude_none=True)
        result = invoke_gateway(binding, "generate", payload, request.user)
        return _chat_response(result)

    @app.post("/v1/embeddings")
    def embeddings(
        request: EmbeddingRequest,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        binding = resolve_binding(authorization)
        payload = {"model": request.model, "input": request.input or []}
        result = invoke_gateway(binding, "embed", payload, None)
        return _embedding_response(result)

    return app


def _chat_response(result: dict[str, Any]) -> JSONResponse:
    """把 gateway InvokeResponse 还原成 OpenAI chat.completion。"""
    text = (result.get("output") or {}).get("text", "")
    usage = result.get("usage") or {}
    headers = _diagnostic_headers(result)
    body = {
        "id": f"chatcmpl-{uuid.uuid4().hex[:24]}",
        "object": "chat.completion",
        "created": int(time.time()),
        # 回报 gateway 实际选用的模型名，WeKnora 侧可观测到降级结果
        "model": result.get("model", ""),
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": text},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": int(usage.get("prompt_tokens", 0) or 0),
            "completion_tokens": int(usage.get("completion_tokens", 0) or 0),
            "total_tokens": int(usage.get("prompt_tokens", 0) or 0)
            + int(usage.get("completion_tokens", 0) or 0),
        },
        # 通道信息塞进 system_fingerprint：OpenAI 协议内的既有字段，
        # WeKnora 不会因此报错，但排障时肉眼可见走了哪条通道。
        "system_fingerprint": f"shilian_{result.get('channel', 'unknown')}",
    }
    return JSONResponse(content=body, headers=headers)


def _embedding_response(result: dict[str, Any]) -> JSONResponse:
    vectors = (result.get("output") or {}).get("embeddings", [])
    usage = result.get("usage") or {}
    headers = _diagnostic_headers(result)
    body = {
        "object": "list",
        "model": result.get("model", ""),
        "data": [
            {"object": "embedding", "index": i, "embedding": vec}
            for i, vec in enumerate(vectors)
        ],
        "usage": {
            "prompt_tokens": int(usage.get("prompt_tokens", 0) or 0),
            "total_tokens": int(usage.get("prompt_tokens", 0) or 0),
        },
    }
    return JSONResponse(content=body, headers=headers)


def _diagnostic_headers(result: dict[str, Any]) -> dict[str, str]:
    """switched_reason / budget_alert 走响应头（协议体内没有位置可放）。"""
    headers: dict[str, str] = {}
    if result.get("switched_reason"):
        # 中文原因必须 URL 编码，否则不是合法的 HTTP header 值
        headers[HEADER_SWITCHED_REASON] = quote(str(result["switched_reason"]))
        logger.warning("模型通道切换：%s", result["switched_reason"])
    if result.get("budget_alert"):
        headers[HEADER_BUDGET_ALERT] = quote(str(result["budget_alert"]))
        logger.warning("预算告警：%s", result["budget_alert"])
    return headers


app = create_app()
