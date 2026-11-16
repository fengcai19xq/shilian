"""拒答策略：统一的内容安全与权限拒答框架。

T16 验收要求："越权拒答回归用例通过"。
现有拒答逻辑分散在 retrieval-proxy（越权返回空）和 decide（kb_empty 不自动转通用），
但 gateway 层没有统一的拒答策略。

本模块在 gateway 调用前检查：
1. 越权：用户密级 < 请求声明的 sensitivity → 拒绝
2. 敏感词：请求内容命中拒答词表 → 拒绝

注意：L4 不出 VPC 由 SensitivityRouter 保证（L4 只路由到 vpc_self_hosted 通道），
本策略不重复拦截 L4 请求——router 已经确保 L4 内容只走 VPC 内模型。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass

logger = logging.getLogger("gateway.refusal")

_SENSITIVITY_ORDER = {"L1": 1, "L2": 2, "L3": 3, "L4": 4}


@dataclass(frozen=True)
class RefusalResult:
    """拒答判定结果。"""

    refused: bool
    reason: str = ""

    @property
    def allowed(self) -> bool:
        return not self.refused


# 默认拒答词表（可扩展）
_DEFAULT_BLOCKED_PATTERNS = [
    re.compile(r"密码|password|passwd", re.IGNORECASE),
    re.compile(r"私钥|private.?key|secret.?key", re.IGNORECASE),
    re.compile(r"身份证号|身份证|id.?card", re.IGNORECASE),
    re.compile(r"银行卡号|card.?number", re.IGNORECASE),
]


class RefusalPolicy:
    """统一拒答策略。"""

    def __init__(
        self,
        blocked_patterns: list[re.Pattern] | None = None,
    ) -> None:
        self._patterns = blocked_patterns or _DEFAULT_BLOCKED_PATTERNS

    def check(
        self,
        user_max_sensitivity: str,
        request_sensitivity: str,
        payload_text: str = "",
    ) -> RefusalResult:
        """检查是否应该拒答。

        Args:
            user_max_sensitivity: 用户的最高密级（L1-L4）。
            request_sensitivity: 请求声明的数据密级（L1-L4）。
            payload_text: 请求文本内容（用于敏感词检测）。

        Returns:
            RefusalResult: refused=True 时应拒绝，reason 说明原因。
        """
        # 1. 越权：用户密级 < 请求密级
        user_level = _SENSITIVITY_ORDER.get(user_max_sensitivity, 0)
        req_level = _SENSITIVITY_ORDER.get(request_sensitivity, 99)
        if user_level < req_level:
            logger.warning(
                "拒答-越权：用户密级 %s < 请求密级 %s",
                user_max_sensitivity,
                request_sensitivity,
            )
            return RefusalResult(
                refused=True,
                reason=f"用户密级 {user_max_sensitivity} 不足以访问 {request_sensitivity} 级内容",
            )

        # 2. 敏感词检测
        if payload_text:
            for pattern in self._patterns:
                if pattern.search(payload_text):
                    logger.warning("拒答-敏感词：命中模式 %s", pattern.pattern)
                    return RefusalResult(
                        refused=True,
                        reason="请求内容包含敏感信息，已拒绝",
                    )

        return RefusalResult(refused=False)
