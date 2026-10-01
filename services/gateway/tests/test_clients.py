"""OpenAI 兼容客户端测试：用 httpx.MockTransport，不发真实请求、不用真实密钥。"""

from __future__ import annotations

import json
import re
from pathlib import Path

import httpx
import pytest

from gateway.clients.base import ModelClient
from gateway.clients.fake import FakeModelClient
from gateway.clients.openai_compatible import (
    MissingApiKeyError,
    OpenAICompatibleClient,
    OpenAICompatibleFactory,
)
from gateway.schemas import Purpose

FAKE_KEY = "test-only-not-a-real-key"


def _client(handler, spec):
    return OpenAICompatibleClient(spec, http_client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_chat_请求与解析(routing_config, monkeypatch):
    spec = routing_config.models["deepseek-v3-enterprise"]
    monkeypatch.setenv(spec.api_key_env, FAKE_KEY)
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["Authorization"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "你好"}}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 3},
            },
        )

    result = _client(handler, spec).invoke(spec, Purpose.generate, {"prompt": "hi", "temperature": 0.1})
    assert seen["url"].endswith("/chat/completions")
    assert seen["auth"] == f"Bearer {FAKE_KEY}"
    assert seen["body"]["model"] == "deepseek-v3-enterprise"
    assert seen["body"]["messages"] == [{"role": "user", "content": "hi"}]
    assert seen["body"]["temperature"] == 0.1
    assert result.output == {"text": "你好"}
    assert (result.prompt_tokens, result.completion_tokens) == (12, 3)


def test_embed_请求与解析(routing_config, monkeypatch):
    spec = routing_config.models["bge-m3-vpc"]
    monkeypatch.setenv(spec.api_key_env, FAKE_KEY)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/embeddings")
        return httpx.Response(
            200, json={"data": [{"embedding": [0.1, 0.2]}], "usage": {"prompt_tokens": 4, "total_tokens": 4}}
        )

    result = _client(handler, spec).invoke(spec, Purpose.embed, {"input": ["abc"]})
    assert result.output == {"embeddings": [[0.1, 0.2]]}
    assert result.prompt_tokens == 4


def test_rerank_请求与解析(routing_config, monkeypatch):
    spec = routing_config.models["bge-m3-vpc"]
    monkeypatch.setenv(spec.api_key_env, FAKE_KEY)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/rerank")
        return httpx.Response(200, json={"results": [{"index": 1, "relevance_score": 0.8}]})

    result = _client(handler, spec).invoke(spec, Purpose.rerank, {"query": "q", "documents": ["a", "b"]})
    assert result.output["results"][0]["index"] == 1


def test_缺密钥环境变量时报错(routing_config, monkeypatch):
    spec = routing_config.models["deepseek-v3-enterprise"]
    monkeypatch.delenv(spec.api_key_env, raising=False)
    client = _client(lambda r: httpx.Response(200, json={}), spec)
    with pytest.raises(MissingApiKeyError):
        client.invoke(spec, Purpose.generate, {"prompt": "hi"})


def test_客户端实现统一接口(routing_config):
    spec = routing_config.models["qwen2.5-vpc"]
    assert isinstance(OpenAICompatibleFactory().get(spec), ModelClient)
    assert isinstance(FakeModelClient(), ModelClient)


def test_代码与配置中不含密钥明文():
    """粗检：源码/配置里不得出现常见密钥形态。"""
    src = Path(__file__).resolve().parents[1] / "src"
    pattern = re.compile(r"(sk-[A-Za-z0-9]{16,}|api_key\s*[:=]\s*['\"][^'\"]{8,})")
    for path in src.rglob("*"):
        if path.suffix in {".py", ".yaml", ".yml"}:
            assert not pattern.search(path.read_text(encoding="utf-8")), f"疑似密钥：{path}"
