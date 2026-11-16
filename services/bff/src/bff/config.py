"""BFF 配置。

设计约束（对应 contracts/retrieval.md / platform.md）：

- 浏览器不再传 principal，也不传 X-User-Id；身份只来自会话。
- 生产模式从 wecom-sync `/identity/me` 换取 principal，权限字段只信服务端。
- `BFF_DEV_MODE=true` 时可用服务端固定的 `BFF_DEV_PRINCIPAL` 做本地/测试联调，
  该开关只影响本进程，客户端无法通过请求体覆盖。

所有 URL 与令牌只从环境变量读取，不在代码里硬编码。
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass

from pydantic import ValidationError

from .models import Principal


class ConfigError(RuntimeError):
    """配置非法。启动即失败，避免带病运行。"""


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    identity_url: str = "http://wecom-sync:8020"
    retrieval_url: str = "http://retrieval-proxy:8000"
    decide_url: str = "http://decide:8000"
    gateway_url: str = "http://gateway:8000"
    ingest_url: str = "http://ingest:8030"
    matcher_url: str = "http://matcher:8010"
    packager_url: str = "http://packager:8080"
    internal_token: str = ""
    dev_mode: bool = False
    dev_principal: str = ""
    trust_user_header: bool = False
    session_cookie_name: str = "shilian_id_token"
    admin_roles: tuple[str, ...] = ("admin", "it_admin")
    kb_scope: tuple[str, ...] = ()
    context_ttl_seconds: int = 900
    context_secret: str = ""
    context_cookie_secure: bool = False
    timeout_seconds: float = 60.0

    @classmethod
    def from_env(cls) -> "Settings":
        dev_mode = _env_bool("BFF_DEV_MODE", False)
        dev_principal = _env("BFF_DEV_PRINCIPAL")
        if dev_mode:
            if not dev_principal:
                raise ConfigError("BFF_DEV_MODE=true 时必须配置 BFF_DEV_PRINCIPAL")
            try:
                Principal.model_validate(json.loads(dev_principal))
            except (json.JSONDecodeError, ValidationError) as exc:
                raise ConfigError("BFF_DEV_PRINCIPAL 不是合法的 principal JSON") from exc

        kb_scope = tuple(
            part.strip() for part in _env("BFF_KB_SCOPE").split(",") if part.strip()
        )
        return cls(
            identity_url=_env("BFF_IDENTITY_URL", "http://wecom-sync:8020").rstrip("/"),
            retrieval_url=_env("BFF_RETRIEVAL_URL", "http://retrieval-proxy:8000").rstrip("/"),
            decide_url=_env("BFF_DECIDE_URL", "http://decide:8000").rstrip("/"),
            gateway_url=_env("BFF_GATEWAY_URL", "http://gateway:8000").rstrip("/"),
            ingest_url=_env("BFF_INGEST_URL", "http://ingest:8030").rstrip("/"),
            matcher_url=_env("BFF_MATCHER_URL", "http://matcher:8010").rstrip("/"),
            packager_url=_env("BFF_PACKAGER_URL", "http://packager:8080").rstrip("/"),
            internal_token=_env("BFF_INTERNAL_TOKEN"),
            dev_mode=dev_mode,
            dev_principal=dev_principal,
            trust_user_header=_env_bool("BFF_TRUST_USER_HEADER", False),
            session_cookie_name=_env("BFF_SESSION_COOKIE", "shilian_id_token"),
            admin_roles=tuple(
                part.strip() for part in _env("BFF_ADMIN_ROLES", "admin,it_admin").split(",") if part.strip()
            ),
            kb_scope=kb_scope,
            context_ttl_seconds=int(_env("BFF_CONTEXT_TTL_SECONDS", "900") or "900"),
            context_secret=_env("BFF_CONTEXT_SECRET"),
            context_cookie_secure=_env_bool("BFF_CONTEXT_COOKIE_SECURE", False),
            timeout_seconds=float(_env("BFF_TIMEOUT_SECONDS", "60") or "60"),
        )

    def dev_principal_model(self) -> Principal | None:
        """仅在 dev_mode 下解析；其它情况返回 None，绝不静默放宽。"""
        if not self.dev_mode or not self.dev_principal:
            return None
        return Principal.model_validate(json.loads(self.dev_principal))
