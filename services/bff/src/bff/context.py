"""会话级检索上下文。

网关的 `sensitivity` 不能由浏览器随意下调：企业问答的片段来自 retrieval，
BFF 在代理检索响应时记住该用户本次上下文里的最高密级，生成时把请求密级
抬到不低于该值。没有检索上下文却带 messages 的生成请求直接拒绝，避免
“先检索 L3、再手工把 sensitivity 改成 L1 绕过脱敏”。

进程内 TTL 只是同实例的快速路径；配置 `BFF_CONTEXT_SECRET` 后，BFF 会额外
签发带 HMAC 的 HttpOnly Cookie，多副本共享同一个密钥即可校验，无需 Redis。
语义不变：**密级下限只由服务端写入**。
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import threading
import time

from .models import SENSITIVITY_ORDER

_LEVEL = {level: index for index, level in enumerate(SENSITIVITY_ORDER)}


def max_sensitivity(*levels: str | None) -> str:
    """返回给定密级中的最高值；忽略未知值。"""
    known = [level for level in levels if level in _LEVEL]
    if not known:
        return "L1"
    return max(known, key=lambda level: _LEVEL[level])


class SensitivityContextStore:
    def __init__(self, ttl_seconds: int = 900) -> None:
        self._ttl = max(1, ttl_seconds)
        self._items: dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()

    def record_hits(self, user_id: str, hits: list[dict]) -> None:
        """记录一次检索响应里的最高密级。"""
        levels = [str(hit.get("sensitivity", "")) for hit in hits if isinstance(hit, dict)]
        highest = max_sensitivity(*levels)
        with self._lock:
            self._items[user_id] = (highest, time.monotonic() + self._ttl)

    def floor_for(self, user_id: str) -> str | None:
        """返回未过期的密级下限；没有上下文返回 None。"""
        now = time.monotonic()
        with self._lock:
            item = self._items.get(user_id)
            if item is None:
                return None
            level, expires_at = item
            if expires_at <= now:
                self._items.pop(user_id, None)
                return None
            return level


# ---------------- 跨副本上下文：签名 Cookie ----------------


def _b64(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def _unb64(value: str) -> str:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding).decode("utf-8")


def sign_context(user_id: str, level: str, secret: str, ttl_seconds: int, now: float | None = None) -> str:
    """生成 `v1.<user>.<level>.<exp>.<sig>` 的无状态上下文凭证。

    用 HMAC 保护用户与密级下限；多副本只要共享 BFF_CONTEXT_SECRET 即可校验，
    不需要 Redis，也不会把密级交给客户端明文控制。
    """
    expires = int((now if now is not None else time.time()) + max(1, ttl_seconds))
    payload = f"v1.{_b64(user_id)}.{level}.{expires}"
    sig = hmac.new(secret.encode("utf-8"), payload.encode("ascii"), hashlib.sha256).hexdigest()
    return f"{payload}.{sig}"


def verify_context(value: str, secret: str, now: float | None = None) -> tuple[str, str] | None:
    """校验签名与过期时间；失败返回 None，调用方必须 fail-closed。"""
    if not value or not secret:
        return None
    parts = value.split(".")
    if len(parts) != 5 or parts[0] != "v1":
        return None
    _, user_b64, level, exp_raw, sig = parts
    payload = f"v1.{user_b64}.{level}.{exp_raw}"
    expected = hmac.new(secret.encode("utf-8"), payload.encode("ascii"), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, sig):
        return None
    try:
        expires = int(exp_raw)
    except ValueError:
        return None
    if expires <= int(now if now is not None else time.time()):
        return None
    if level not in _LEVEL:
        return None
    try:
        return _unb64(user_b64), level
    except (ValueError, UnicodeDecodeError, binascii.Error):
        return None
