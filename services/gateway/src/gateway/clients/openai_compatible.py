"""OpenAI 兼容协议的客户端实现（chat/completions、embeddings）。

密钥只从环境变量读（ModelSpec.api_key_env），代码与配置里都不出现密钥明文。
"""

from __future__ import annotations

import os
from typing import Any

import httpx

from ..config import ModelSpec
from ..schemas import Purpose
from .base import ClientResult


class MissingApiKeyError(RuntimeError):
    """环境变量里没有配置该模型的密钥。"""


class OpenAICompatibleClient:
    """一个 endpoint 对应一个客户端实例。"""

    def __init__(
        self, spec: ModelSpec, http_client: httpx.Client | None = None, timeout: float = 60.0
    ) -> None:
        self._spec = spec
        self._timeout = timeout
        self._http = http_client

    def invoke(self, spec: ModelSpec, purpose: Purpose, payload: dict[str, Any]) -> ClientResult:
        path, body = self._build_request(spec, purpose, payload)
        headers = {
            "Authorization": f"Bearer {self._api_key(spec)}",
            "Content-Type": "application/json",
        }
        url = spec.endpoint.rstrip("/") + path
        client = self._http or httpx.Client(timeout=self._timeout)
        try:
            response = client.post(url, json=body, headers=headers)
            response.raise_for_status()
            data = response.json()
        finally:
            if self._http is None:
                client.close()
        return self._parse(purpose, data)

    @staticmethod
    def _api_key(spec: ModelSpec) -> str:
        key = os.environ.get(spec.api_key_env, "")
        if not key:
            raise MissingApiKeyError(f"模型 {spec.name} 的密钥环境变量 {spec.api_key_env} 未配置")
        return key

    @staticmethod
    def _build_request(
        spec: ModelSpec, purpose: Purpose, payload: dict[str, Any]
    ) -> tuple[str, dict[str, Any]]:
        if purpose in (Purpose.generate, Purpose.decide):
            messages = payload.get("messages")
            if messages is None:
                messages = [{"role": "user", "content": payload.get("prompt", "")}]
            body: dict[str, Any] = {"model": spec.name, "messages": messages}
            for key in ("temperature", "max_tokens", "top_p", "response_format"):
                if key in payload:
                    body[key] = payload[key]
            return "/chat/completions", body
        if purpose is Purpose.embed:
            return "/embeddings", {"model": spec.name, "input": payload.get("input", [])}
        # rerank 走 OpenAI 兼容网关常见的 /rerank 扩展端点
        return "/rerank", {
            "model": spec.name,
            "query": payload.get("query", ""),
            "documents": payload.get("documents", []),
            "top_n": payload.get("top_n"),
        }

    @staticmethod
    def _parse(purpose: Purpose, data: dict[str, Any]) -> ClientResult:
        usage = data.get("usage") or {}
        prompt_tokens = int(usage.get("prompt_tokens", usage.get("total_tokens", 0)) or 0)
        completion_tokens = int(usage.get("completion_tokens", 0) or 0)

        if purpose in (Purpose.generate, Purpose.decide):
            choices = data.get("choices") or []
            text = choices[0].get("message", {}).get("content", "") if choices else ""
            output: dict[str, Any] = {"text": text}
        elif purpose is Purpose.embed:
            output = {"embeddings": [item.get("embedding", []) for item in data.get("data") or []]}
        else:
            output = {"results": data.get("results") or []}

        return ClientResult(output=output, prompt_tokens=prompt_tokens, completion_tokens=completion_tokens)


class OpenAICompatibleFactory:
    """默认工厂：所有模型都按 OpenAI 兼容协议调用，按模型名缓存客户端。"""

    def __init__(self, http_client: httpx.Client | None = None) -> None:
        self._http = http_client
        self._cache: dict[str, OpenAICompatibleClient] = {}

    def get(self, spec: ModelSpec) -> OpenAICompatibleClient:
        if spec.name not in self._cache:
            self._cache[spec.name] = OpenAICompatibleClient(spec, http_client=self._http)
        return self._cache[spec.name]
