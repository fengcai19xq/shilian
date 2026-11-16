"""会话身份 → 权限主体。

浏览器只提供会话凭证（Cookie / Authorization），不提供权限字段。
BFF 向 wecom-sync `/identity/me` 换取 principal；`dept / roles / max_sensitivity /
projects` 只来自身份服务，客户端请求体里的同名字段一律被丢弃。

`BFF_DEV_MODE=true` 时允许使用服务端固定的 `BFF_DEV_PRINCIPAL` 做本地/测试联调；
`BFF_TRUST_USER_HEADER=true` 只用于“网关已在外部完成鉴权、网络不可直达”的过渡部署，
默认关闭。
"""

from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import unquote

import httpx
from pydantic import ValidationError

from .config import Settings
from .models import Principal


def cookie_value(cookie_header: str, name: str) -> str:
    """从 Cookie 头里取指定值；不存在返回空串。"""
    if not cookie_header or not name:
        return ""
    for part in cookie_header.split(";"):
        key, sep, value = part.strip().partition("=")
        if sep and key == name:
            return unquote(value.strip())
    return ""


class Unauthenticated(RuntimeError):
    """没有可用会话，或会话已失效。"""


class IdentityUnavailable(RuntimeError):
    """身份服务不可用/返回非法。调用方应返回 503，不能降级为匿名。"""


@dataclass
class PrincipalResolver:
    settings: Settings
    http: httpx.AsyncClient

    async def resolve(
        self,
        authorization: str | None,
        x_user_id: str | None,
        cookie: str | None = None,
    ) -> Principal:
        dev = self.settings.dev_principal_model()
        if dev is not None:
            # 静态主体只在 dev_mode 下生效，且完全不看请求里的 principal/身份头。
            return dev

        headers: dict[str, str] = {}
        if authorization:
            headers["Authorization"] = authorization
        elif cookie:
            # 会话 Cookie 只交给身份服务，不转发给业务服务。
            # IdP 把 id_token 放在 HttpOnly Cookie 时，BFF 转成 Authorization 给 wecom-sync；
            # 如果身份服务本身就是基于 Cookie 的会话，Cookie 也一并透传。
            headers["Cookie"] = cookie
            token = cookie_value(cookie, self.settings.session_cookie_name)
            if token:
                headers["Authorization"] = f"Bearer {token}"
        elif self.settings.trust_user_header and x_user_id:
            # 过渡模式：只把外部网关注入的用户标识交给身份服务换权限，
            # 不信任浏览器直接给出的 dept / max_sensitivity / projects。
            headers["X-User-Id"] = x_user_id
        else:
            raise Unauthenticated("缺少会话身份")

        try:
            resp = await self.http.get(f"{self.settings.identity_url}/identity/me", headers=headers)
        except httpx.HTTPError as exc:
            raise IdentityUnavailable("身份服务不可达") from exc

        if resp.status_code in {401, 403, 404, 410}:
            raise Unauthenticated("未登录或会话已过期")
        if resp.status_code >= 500:
            raise IdentityUnavailable(f"身份服务返回 {resp.status_code}")
        if resp.status_code != 200:
            raise IdentityUnavailable(f"身份服务返回 {resp.status_code}")

        try:
            payload = resp.json()
            return Principal.model_validate(payload)
        except (ValueError, ValidationError) as exc:
            # 不回显身份服务响应体，避免把内部字段带到错误信息里
            raise IdentityUnavailable("身份服务返回的主体不合法") from exc
