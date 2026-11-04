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


def test_仅有query时构造成用户消息(routing_config, monkeypatch):
    """契约规定 generate 必填 payload.query（前端通用问答就只发它）。

    回归防护：此前只认 prompt，query 被忽略后发出空 content，真实上游返回 400。
    """
    spec = routing_config.models["deepseek-v3-enterprise"]
    monkeypatch.setenv(spec.api_key_env, FAKE_KEY)
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "答"}}],
                                         "usage": {"prompt_tokens": 3, "completion_tokens": 1}})

    _client(handler, spec).invoke(spec, Purpose.generate, {"query": "授信流程是什么"})
    assert seen["body"]["messages"] == [{"role": "user", "content": "授信流程是什么"}]


def test_prompt缺失时用query兜底(routing_config, monkeypatch):
    """老字段 prompt 仍在时不动其行为（向后兼容）；缺 prompt 才用契约字段 query。"""
    spec = routing_config.models["deepseek-v3-enterprise"]
    monkeypatch.setenv(spec.api_key_env, FAKE_KEY)
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "答"}}], "usage": {}})

    client = _client(handler, spec)
    # 只有 prompt：老调用方行为不变
    client.invoke(spec, Purpose.generate, {"prompt": "老字段"})
    assert seen["body"]["messages"][0]["content"] == "老字段"
    # 两者都有：prompt 优先，保证已上线调用方不被改写
    client.invoke(spec, Purpose.generate, {"query": "新字段", "prompt": "老字段"})
    assert seen["body"]["messages"][0]["content"] == "老字段"


def test_配置上游模型名后发往厂商用真实名(routing_config, monkeypatch):
    """内部 name 与厂商 model id 解耦：改上游名不影响前端选择与出账键。

    回归防护：上游把 deepseek-v3 改名后，只配 upstream_model，
    发往厂商的请求体用真实名，但 spec.name（记账/前端 preferred_model）不变。
    """
    from dataclasses import replace
    base = routing_config.models["deepseek-v3-enterprise"]
    spec = replace(base, upstream_model="deepseek-flash")
    monkeypatch.setenv(spec.api_key_env, FAKE_KEY)
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "答"}}], "usage": {}})

    _client(handler, spec).invoke(spec, Purpose.generate, {"prompt": "hi"})
    assert seen["body"]["model"] == "deepseek-flash"   # 发往厂商
    assert spec.name == "deepseek-v3-enterprise"        # 内部标识不变


def test_未配上游模型名时回退内部名(routing_config):
    """缺省（空 upstream_model）时 api_model 等于 name，既有配置零行为变化。"""
    spec = routing_config.models["deepseek-v3-enterprise"]
    assert spec.upstream_model == ""
    assert spec.api_model == spec.name
