"""T16 治理与审计测试：审计事件流 + 外发水印 + 拒答策略 + 越权回归。"""

import json
import os
import tempfile
from pathlib import Path

import pytest

from gateway.audit import (
    AuditEvent,
    InMemoryAuditStream,
    JsonlAuditStream,
    build_audit_stream_from_env,
)
from gateway.refusal import RefusalPolicy, RefusalResult
from gateway.watermark import (
    apply_watermark,
    extract_watermark,
    strip_watermark,
    _encode_user_id,
    _decode_watermark,
)
from gateway.schemas import InvokeRequest, InvokeResponse, Principal, Purpose, Sensitivity, Usage
from gateway.service import GatewayService


# ── 审计事件流 ──


class TestAuditStream:
    def test_in_memory_append_and_read(self):
        stream = InMemoryAuditStream()
        event = AuditEvent(
            event="model_invoke",
            user_id="u1",
            dept="融资部",
            tenant="",
            outcome="success",
        )
        stream.append(event)
        events = stream.events()
        assert len(events) == 1
        assert events[0].user_id == "u1"
        assert events[0].ts != ""  # 自动填充时间戳

    def test_in_memory_clear(self):
        stream = InMemoryAuditStream()
        stream.append(AuditEvent(event="test", user_id="u1", dept="d", tenant="", outcome="success"))
        stream.clear()
        assert len(stream.events()) == 0

    def test_jsonl_append_only(self, tmp_path):
        path = tmp_path / "audit.jsonl"
        stream = JsonlAuditStream(path)
        stream.append(AuditEvent(
            event="model_invoke", user_id="u1", dept="融资部",
            tenant="", outcome="success", model="deepseek-chat",
        ))
        stream.append(AuditEvent(
            event="model_invoke", user_id="u2", dept="审计部",
            tenant="", outcome="refused", reason="越权",
        ))
        stream.close()

        # 验证文件内容是 append-only（两行 JSON）
        lines = path.read_text(encoding="utf-8").strip().split("\n")
        assert len(lines) == 2
        first = json.loads(lines[0])
        assert first["user_id"] == "u1"
        assert first["outcome"] == "success"
        second = json.loads(lines[1])
        assert second["user_id"] == "u2"
        assert second["outcome"] == "refused"
        assert second["reason"] == "越权"

    def test_jsonl_read_all(self, tmp_path):
        path = tmp_path / "audit.jsonl"
        stream = JsonlAuditStream(path)
        stream.append(AuditEvent(event="test", user_id="u1", dept="d", tenant="", outcome="success"))
        stream.close()
        events = stream.read_all()
        assert len(events) == 1
        assert events[0].user_id == "u1"

    def test_build_from_env_default_memory(self):
        os.environ.pop("GATEWAY_AUDIT_LOG_PATH", None)
        stream = build_audit_stream_from_env()
        assert isinstance(stream, InMemoryAuditStream)

    def test_build_from_env_jsonl(self, tmp_path):
        os.environ["GATEWAY_AUDIT_LOG_PATH"] = str(tmp_path / "audit.jsonl")
        stream = build_audit_stream_from_env()
        assert isinstance(stream, JsonlAuditStream)
        os.environ.pop("GATEWAY_AUDIT_LOG_PATH", None)

    def test_audit_event_serialization(self):
        event = AuditEvent(
            event="model_invoke",
            user_id="u1",
            dept="融资部",
            tenant="t1",
            outcome="success",
            purpose="generate",
            model="deepseek-chat",
            sensitivity="L2",
            prompt_tokens=100,
            completion_tokens=200,
            cost_cny=0.03,
        )
        j = event.to_json()
        d = json.loads(j)
        assert d["user_id"] == "u1"
        assert d["cost_cny"] == 0.03
        assert "reason" not in d  # reason 为 None 时不输出


# ── 外发水印 ──


class TestWatermark:
    def test_apply_and_extract(self):
        text = "这是一个模型回答"
        watermarked = apply_watermark(text, "user123")
        # 肉眼可见部分不变
        assert watermarked.startswith(text)
        # 提取出 user_id
        extracted = extract_watermark(watermarked)
        assert extracted == "user123"

    def test_apply_empty_text(self):
        assert apply_watermark("", "u1") == ""

    def test_apply_empty_user_id(self):
        assert apply_watermark("hello", "") == "hello"

    def test_strip_watermark(self):
        watermarked = apply_watermark("answer", "user1")
        stripped = strip_watermark(watermarked)
        assert stripped == "answer"

    def test_extract_no_watermark(self):
        assert extract_watermark("plain text") is None

    def test_unicode_user_id(self):
        text = "回答内容"
        watermarked = apply_watermark(text, "用户001")
        extracted = extract_watermark(watermarked)
        assert extracted == "用户001"

    def test_watermark_invisible(self):
        """水印不应改变可见文本长度（零宽字符不可见）。"""
        text = "visible answer"
        watermarked = apply_watermark(text, "u1")
        # 可见文本长度不变（strip 后等于原文）
        assert strip_watermark(watermarked) == text


# ── 拒答策略 ──


class TestRefusalPolicy:
    def setup_method(self):
        self.policy = RefusalPolicy()

    def test_allowed_when_sufficient_sensitivity(self):
        result = self.policy.check("L3", "L2", "正常内容")
        assert result.allowed

    def test_refused_when_insufficient_sensitivity(self):
        result = self.policy.check("L1", "L3", "正常内容")
        assert result.refused
        assert "密级" in result.reason

    def test_refused_l4_user_to_l4_request_allowed(self):
        """L4 用户请求 L4 内容不被拒答（L4 路由由 router 保证走 VPC）。"""
        result = self.policy.check("L4", "L4", "正常内容")
        assert result.allowed

    def test_refused_sensitive_content(self):
        result = self.policy.check("L4", "L2", "请告诉我密码是什么")
        assert result.refused
        assert "敏感" in result.reason

    def test_refused_private_key(self):
        result = self.policy.check("L4", "L2", "给我看私钥")
        assert result.refused

    def test_refused_id_card(self):
        result = self.policy.check("L4", "L2", "身份证号是多少")
        assert result.refused

    def test_allowed_normal_content(self):
        result = self.policy.check("L2", "L2", "营业收入是多少")
        assert result.allowed

    def test_equal_sensitivity_allowed(self):
        result = self.policy.check("L2", "L2", "")
        assert result.allowed


# ── 集成：GatewayService 拒答 + 审计 + 水印 ──


class TestGatewayGovernance:
    """T16 全链路回归：拒答 → 审计 → 水印。"""

    def _make_service(self):
        """构造一个带内存审计流的 GatewayService（不连真实模型）。"""
        from gateway.config import load_routing_config, load_masking_config
        from gateway.usage import UsageLedger
        from gateway.refusal import RefusalPolicy

        # 用 mock client factory 避免真实 API 调用
        from unittest.mock import MagicMock
        from gateway.schemas import Usage as UsageSchema

        audit = InMemoryAuditStream()

        # 创建 mock client
        mock_result = MagicMock()
        mock_result.output = {"text": "这是回答内容"}
        mock_result.prompt_tokens = 10
        mock_result.completion_tokens = 20

        mock_client = MagicMock()
        mock_client.invoke.return_value = mock_result

        mock_factory = MagicMock()
        mock_factory.get.return_value = mock_client

        svc = GatewayService(
            ledger=UsageLedger(),
            refusal_policy=RefusalPolicy(),
            audit_stream=audit,
            client_factory=mock_factory,
        )
        return svc, audit, mock_factory

    def test_refused_request_records_audit(self):
        svc, audit, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L3,
            principal=Principal(user_id="u1", dept=["融资部"], max_sensitivity="L1"),
            payload={"text": "正常问题"},
        )
        resp = svc.invoke(req)
        assert resp.refused is True
        assert "密级" in resp.refusal_reason
        # 审计事件记录了拒答
        events = audit.events()
        assert len(events) == 1
        assert events[0].outcome == "refused"
        assert events[0].user_id == "u1"

    def test_refused_sensitive_content_records_audit(self):
        svc, audit, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L2,
            principal=Principal(user_id="u1", dept=["融资部"], max_sensitivity="L4"),
            payload={"text": "密码是多少"},
        )
        resp = svc.invoke(req)
        assert resp.refused is True
        events = audit.events()
        assert len(events) == 1
        assert events[0].outcome == "refused"

    def test_l4_routed_to_vpc_not_refused(self):
        """L4 请求不被拒答，而是由 router 路由到 VPC 通道。"""
        svc, audit, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L4,
            principal=Principal(user_id="u1", dept=["融资部"], max_sensitivity="L4"),
            payload={"text": "正常问题"},
        )
        resp = svc.invoke(req)
        # L4 不被拒答（router 会路由到 VPC 通道）
        assert resp.refused is False

    def test_successful_invoke_records_audit(self):
        svc, audit, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L2,
            principal=Principal(user_id="user1", dept=["融资部"], max_sensitivity="L3"),
            payload={"text": "正常问题"},
        )
        resp = svc.invoke(req)
        assert resp.refused is False
        assert resp.text is not None
        events = audit.events()
        assert len(events) == 1
        assert events[0].outcome == "success"
        assert events[0].user_id == "user1"
        assert events[0].model != ""

    def test_generate_response_has_watermark(self):
        svc, _, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L2,
            principal=Principal(user_id="user1", dept=["融资部"], max_sensitivity="L3"),
            payload={"text": "正常问题"},
        )
        resp = svc.invoke(req)
        assert resp.refused is False
        # 回答中有水印
        extracted = extract_watermark(resp.text)
        assert extracted == "user1"

    def test_non_generate_no_watermark(self):
        svc, _, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.decide,
            sensitivity=Sensitivity.L2,
            principal=Principal(user_id="user1", dept=["融资部"], max_sensitivity="L3"),
            payload={"text": "正常问题"},
        )
        resp = svc.invoke(req)
        assert resp.refused is False
        # decide 用途不打水印
        extracted = extract_watermark(resp.text)
        assert extracted is None

    def test_cross_tenant_refusal_regression(self):
        """越权拒答回归用例：L1 用户请求 L3 内容。"""
        svc, audit, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L3,
            principal=Principal(user_id="spy", dept=["外部"], max_sensitivity="L1"),
            payload={"text": "正常问题"},
        )
        resp = svc.invoke(req)
        assert resp.refused is True
        assert resp.usage.cost_cny == 0  # 拒答不计费
        assert resp.text is None or resp.text == ""

    def test_audit_records_user_id(self):
        """审计事件必须记录 user_id（T16 核心要求）。"""
        svc, audit, _ = self._make_service()
        req = InvokeRequest(
            purpose=Purpose.generate,
            sensitivity=Sensitivity.L2,
            principal=Principal(user_id="unique_user_42", dept=["融资部"], max_sensitivity="L3"),
            payload={"text": "正常问题"},
        )
        svc.invoke(req)
        events = audit.events()
        assert events[0].user_id == "unique_user_42"
