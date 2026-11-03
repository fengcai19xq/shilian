"""Spike 离线验证：KB 编码语义 + gateway shim 的红线保持。

不依赖 Docker / WeKnora 实例 / 真实模型：gateway 用假客户端注入，
验证的是"shim 是否正确把 WeKnora 的调用翻译成带密级的 InvokeRequest，
以及 gateway 的脱敏/降级/切换原因是否被正确保留并透出"。

真实 WeKnora 侧的验证（H1/H2/H3）见 spike/README.md 的清单，需机房环境执行。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any
from urllib.parse import unquote

import pytest
from fastapi.testclient import TestClient

from ..gateway_shim import HEADER_SWITCHED_REASON, EnvKeyRegistry, KeyBinding, create_app
from ..kb_encoding import (
    PROJECT_NONE,
    SENSITIVITY_ORDER,
    EncodingError,
    build_scope,
    expand_sensitivity,
    kb_name,
    kb_provisioning_plan,
    parse_kb_name,
    project_tag,
    slug,
    visible_project_tags,
)

TENANT = "acme"


# ---------- A. KB 编码 ----------


def test_expand_sensitivity_is_cumulative():
    assert expand_sensitivity("L1") == ["L1"]
    assert expand_sensitivity("L3") == ["L1", "L2", "L3"]
    assert expand_sensitivity("L4") == list(SENSITIVITY_ORDER)


def test_expand_sensitivity_rejects_unknown():
    with pytest.raises(EncodingError):
        expand_sensitivity("L9")
    with pytest.raises(EncodingError):
        expand_sensitivity("")


def test_kb_name_encodes_both_sensitivity_and_dept():
    """KB 名必须同时带密级与部门——单个 IN 过滤要表达两个 AND 维度。"""
    name = kb_name(TENANT, "L3", "融资部")
    assert name.startswith(f"sl-{TENANT}-L3-")
    assert name.isascii()
    assert parse_kb_name(name) == (TENANT, "L3", slug("融资部"))


def test_kb_name_is_reversible_for_every_dept_and_sensitivity():
    """编码必须可逆：否则 ingest 打标与检索解析会对不上。"""
    for dept in ["融资部", "财务共享", "risk-control", "数智化中心"]:
        for sensitivity in SENSITIVITY_ORDER:
            name = kb_name(TENANT, sensitivity, dept)
            tenant, parsed_sensitivity, parsed_dept = parse_kb_name(name)
            assert tenant == TENANT
            assert parsed_sensitivity == sensitivity
            assert parsed_dept == slug(dept)


def test_slug_is_collision_free_across_depts():
    """中文部门按码点 hex 化，不同部门不得撞出同一个 KB。"""
    names = {slug(d) for d in ["融资部", "财务共享", "数智化中心", "风控部"]}
    assert len(names) == 4


def test_slug_keeps_ascii_readable():
    assert slug("risk-control") == "risk-control"
    assert slug("Risk_Control") == "risk_control"


def test_kb_name_rejects_bad_sensitivity():
    with pytest.raises(EncodingError):
        kb_name(TENANT, "L5", "融资部")
    with pytest.raises(EncodingError):
        parse_kb_name("sl-acme-L9-6881")
    with pytest.raises(EncodingError):
        parse_kb_name("not-a-shilian-kb")


def test_visible_kbs_is_dept_cross_visible_sensitivity():
    scope = build_scope(TENANT, ["融资部", "财务共享"], "L2")
    assert scope.has_any_access is True
    # 2 部门 × (L1,L2) = 4 个 KB；L3/L4 一律不可见
    assert len(scope.knowledge_base_ids) == 4
    assert all("-L3-" not in kb and "-L4-" not in kb for kb in scope.knowledge_base_ids)


def test_no_dept_means_no_access_and_no_request():
    """无部门授权 = 完全无权：不发检索请求，返回空（对齐 retrieval-proxy 红线）。"""
    scope = build_scope(TENANT, [], "L4")
    assert scope.has_any_access is False
    assert scope.knowledge_base_ids == []


def test_knowledge_ids_always_empty():
    """陷阱 1：knowledge_ids 与 KB 取并集，用它承载 project 会绕开密级隔离。

    这条断言是安全不变量，不是风格偏好——任何后续改动都必须让它继续为真。
    """
    scope = build_scope(TENANT, ["融资部"], "L3", projects=["PRJ-2026-CITIC"])
    assert scope.knowledge_ids == []


def test_no_project_authorization_means_no_tag_filter():
    """projects 为空 -> 不下发 tag_ids -> KB 内全部文档可见（不限项目）。"""
    scope = build_scope(TENANT, ["融资部"], "L2")
    assert scope.tag_names == []


def test_project_tags_include_none_sentinel():
    """陷阱：WeKnora 的 tag IN 过滤没有"NULL 也放行"语义。

    RAGFlow 版 `_is_allowed` 对 chunk.project is None 返回 True（未标注项目的文档
    所有人可见）。WeKnora 侧必须补哨兵标签，否则这些文档会被静默过滤掉，
    造成"权限收紧"的假象——功能上看不出来，但业务会漏料。
    """
    tags = visible_project_tags(["PRJ-2026-CITIC"])
    assert project_tag("PRJ-2026-CITIC") in tags
    assert project_tag(None) == f"project::{PROJECT_NONE}"
    assert project_tag(None) in tags


def test_project_tag_normalizes_blank():
    assert project_tag("  ") == project_tag(None)


def test_provisioning_plan_covers_full_matrix():
    plan = kb_provisioning_plan(TENANT, ["融资部", "财务共享"])
    assert len(plan) == len(SENSITIVITY_ORDER) * 2
    assert len(set(plan)) == len(plan)


# ---------- B. gateway shim ----------


@dataclass
class FakeGateway:
    """假 gateway：记录请求体，按脚本返回 InvokeResponse 形状的字典。"""

    responses: list[dict[str, Any]] = field(default_factory=list)
    calls: list[dict[str, Any]] = field(default_factory=list)
    default_response: dict[str, Any] = field(
        default_factory=lambda: {
            "model": "deepseek-v3-enterprise",
            "channel": "enterprise_api",
            "switched_reason": None,
            "output": {"text": "你好"},
            "usage": {"prompt_tokens": 12, "completion_tokens": 3, "cost_cny": 0.001},
        }
    )

    def invoke(self, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(body)
        if self.responses:
            return self.responses.pop(0)
        return self.default_response

    @property
    def last(self) -> dict[str, Any]:
        return self.calls[-1]


def make_client(gateway: FakeGateway, bindings: dict[str, KeyBinding] | None = None):
    registry = EnvKeyRegistry(
        bindings=bindings
        if bindings is not None
        else {
            "sk-l3-finance": KeyBinding(TENANT, "融资部", "L3"),
            "sk-l1-finance": KeyBinding(TENANT, "融资部", "L1"),
        }
    )
    app = create_app(registry=registry, gateway=gateway)
    return TestClient(app), gateway


def auth(key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {key}"}


def test_healthz_open():
    client, _ = make_client(FakeGateway())
    assert client.get("/healthz").json() == {"status": "ok"}


def test_chat_passes_sensitivity_and_principal_to_gateway():
    """核心断言：WeKnora 的调用必须被翻译成带密级 + principal 的 InvokeRequest。"""
    client, gw = make_client(FakeGateway())
    resp = client.post(
        "/v1/chat/completions",
        json={
            "model": "deepseek-v3",
            "messages": [{"role": "user", "content": "中信银行要哪些资料"}],
            "temperature": 0.2,
        },
        headers=auth("sk-l3-finance"),
    )
    assert resp.status_code == 200
    body = gw.last
    assert body["purpose"] == "generate"
    assert body["sensitivity"] == "L3"
    assert body["principal"] == {"user_id": f"weknora-{TENANT}", "dept": ["融资部"]}
    assert body["preferred_model"] == "deepseek-v3"
    assert body["payload"]["temperature"] == 0.2


def test_response_is_valid_openai_shape():
    """WeKnora 用标准 OpenAI 解析器，响应形状错了它直接报错。"""
    client, _ = make_client(FakeGateway())
    resp = client.post(
        "/v1/chat/completions",
        json={"model": "deepseek-v3", "messages": [{"role": "user", "content": "hi"}]},
        headers=auth("sk-l3-finance"),
    )
    data = resp.json()
    assert data["object"] == "chat.completion"
    assert data["choices"][0]["message"]["content"] == "你好"
    assert data["choices"][0]["finish_reason"] == "stop"
    assert data["usage"]["total_tokens"] == 15
    # 通道信息借 system_fingerprint 透出，便于排障肉眼确认走了哪条通道
    assert data["system_fingerprint"] == "shilian_enterprise_api"
    assert data["model"] == "deepseek-v3-enterprise"


def test_l3_key_routes_to_l3_and_l1_key_to_l1():
    """密级由 key 决定，不由请求体决定——WeKnora 无法伪造密级。"""
    client, gw = make_client(FakeGateway())
    client.post(
        "/v1/chat/completions",
        json={"model": "m", "messages": [{"role": "user", "content": "a"}]},
        headers=auth("sk-l3-finance"),
    )
    assert gw.last["sensitivity"] == "L3"
    client.post(
        "/v1/chat/completions",
        json={"model": "m", "messages": [{"role": "user", "content": "a"}]},
        headers=auth("sk-l1-finance"),
    )
    assert gw.last["sensitivity"] == "L1"


def test_body_cannot_override_sensitivity():
    """请求体里塞 sensitivity 必须被忽略。"""
    client, gw = make_client(FakeGateway())
    client.post(
        "/v1/chat/completions",
        json={
            "model": "m",
            "messages": [{"role": "user", "content": "a"}],
            "sensitivity": "L1",
            "principal": {"user_id": "attacker", "dept": ["风控部"]},
        },
        headers=auth("sk-l3-finance"),
    )
    assert gw.last["sensitivity"] == "L3"
    assert gw.last["principal"]["dept"] == ["融资部"]


def test_switched_reason_surfaces_in_header():
    """红线 4：切换必须可观测。协议体内无位置，走响应头 + gateway 侧日志。"""
    gw = FakeGateway(
        responses=[
            {
                "model": "qwen2.5-vpc",
                "channel": "vpc_self_hosted",
                "switched_reason": "L3 内容不允许通用公有云 API通道，已降级到合规通道 VPC 内自部署",
                "output": {"text": "ok"},
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "cost_cny": 0.0},
            }
        ]
    )
    client, _ = make_client(gw)
    resp = client.post(
        "/v1/chat/completions",
        json={"model": "deepseek-v3", "messages": [{"role": "user", "content": "x"}]},
        headers=auth("sk-l3-finance"),
    )
    assert resp.status_code == 200
    assert "降级到合规通道" in unquote(resp.headers[HEADER_SWITCHED_REASON])
    assert resp.json()["system_fingerprint"] == "shilian_vpc_self_hosted"


def test_budget_alert_surfaces_in_header():
    gw = FakeGateway(
        responses=[
            {
                "model": "m",
                "channel": "enterprise_api",
                "switched_reason": None,
                "output": {"text": "ok"},
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "cost_cny": 0.0},
                "budget_alert": "融资部 已达月度预算 80%",
            }
        ]
    )
    client, _ = make_client(gw)
    resp = client.post(
        "/v1/chat/completions",
        json={"model": "m", "messages": [{"role": "user", "content": "x"}]},
        headers=auth("sk-l3-finance"),
    )
    assert "80%" in unquote(resp.headers["X-Shilian-Budget-Alert"])


def test_missing_or_bad_key_rejected():
    client, gw = make_client(FakeGateway())
    payload = {"model": "m", "messages": [{"role": "user", "content": "x"}]}
    assert client.post("/v1/chat/completions", json=payload).status_code == 401
    assert client.post(
        "/v1/chat/completions", json=payload, headers=auth("Bearer sk-stolen")
    ).status_code == 403
    assert client.post(
        "/v1/chat/completions", json=payload, headers=auth("sk-stolen")
    ).status_code == 403
    assert gw.calls == []  # 未授权请求绝不下发到 gateway


def test_stream_not_silently_downgraded():
    """未实现流式时必须显式 501，不能静默降级成非流式让 WeKnora 解析错乱。"""
    client, gw = make_client(FakeGateway())
    resp = client.post(
        "/v1/chat/completions",
        json={"model": "m", "messages": [{"role": "user", "content": "x"}], "stream": True},
        headers=auth("sk-l3-finance"),
    )
    assert resp.status_code == 501
    assert gw.calls == []


def test_embeddings_maps_to_embed_purpose():
    client, gw = make_client(FakeGateway())
    gw.default_response = {
        "model": "bge-m3-enterprise",
        "channel": "enterprise_api",
        "switched_reason": None,
        "output": {"embeddings": [[0.1, 0.2], [0.3, 0.4]]},
        "usage": {"prompt_tokens": 5, "completion_tokens": 0, "cost_cny": 0.0},
    }
    resp = client.post(
        "/v1/embeddings",
        json={"model": "bge-m3", "input": ["中信银行", "审计报告"]},
        headers=auth("sk-l3-finance"),
    )
    assert resp.status_code == 200
    assert gw.last["purpose"] == "embed"
    assert gw.last["sensitivity"] == "L3"
    data = resp.json()
    assert data["object"] == "list"
    assert len(data["data"]) == 2
    assert data["data"][0]["embedding"] == [0.1, 0.2]


def test_gateway_error_does_not_leak_upstream_body():
    """红线 2 延伸：上游错误信息不得透出，避免夹带文档内容片段。"""
    from fastapi import HTTPException

    class BoomGateway:
        def invoke(self, body: dict[str, Any]) -> dict[str, Any]:
            raise HTTPException(status_code=502, detail="模型网关返回 422")

    app = create_app(
        registry=EnvKeyRegistry(bindings={"sk-l3-finance": KeyBinding(TENANT, "融资部", "L3")}),
        gateway=BoomGateway(),
    )
    client = TestClient(app, raise_server_exceptions=False)
    resp = client.post(
        "/v1/chat/completions",
        json={"model": "m", "messages": [{"role": "user", "content": "x"}]},
        headers=auth("sk-l3-finance"),
    )
    assert resp.status_code == 502
    # 只允许透出通用状态码描述：不得带密级、通道、模型名或任何上游原文
    assert resp.json() == {"detail": "模型网关返回 422"}
    for leaked in ("L3", "enterprise_api", "vpc", "融资部", "deepseek"):
        assert leaked not in resp.text


def test_env_registry_skips_malformed_entries():
    """配置解析要容错但不得静默放宽：格式非法的 key 直接忽略。"""
    import os

    monkey_env = {
        "SHIM_KEY_1": "sk-ok:acme:融资部:L3",
        "SHIM_KEY_2": "bad-format",
        "SHIM_KEY_3": "",
        "OTHER_VAR": "x:y:z:L1",
    }
    old = {k: os.environ.get(k) for k in monkey_env}
    os.environ.update({k: v for k, v in monkey_env.items() if v})
    for k, v in monkey_env.items():
        if not v:
            os.environ.pop(k, None)
    try:
        registry = EnvKeyRegistry.from_env()
        assert set(registry.bindings) == {"sk-ok"}
        assert registry.lookup("sk-ok").sensitivity == "L3"
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
