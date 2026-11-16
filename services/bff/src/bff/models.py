"""BFF 内部数据结构。字段与 contracts/retrieval.md / platform.md 对齐（snake_case）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Sensitivity = Literal["L1", "L2", "L3", "L4"]
SENSITIVITY_ORDER: tuple[str, ...] = ("L1", "L2", "L3", "L4")
DEFAULT_TENANT = "default"


class Principal(BaseModel):
    """服务端解析出的权限主体。

    tenant 在身份服务尚未返回时补默认值，兼容当前单租户部署。
    """

    user_id: str
    dept: list[str] = Field(default_factory=list)
    roles: list[str] = Field(default_factory=list)
    max_sensitivity: Sensitivity
    projects: list[str] = Field(default_factory=list)
    tenant: str = DEFAULT_TENANT

    def as_downstream(self) -> dict:
        """给下游服务的 principal 体。只保留权限判定需要的字段。"""
        return {
            "user_id": self.user_id,
            "dept": list(self.dept),
            "roles": list(self.roles),
            "max_sensitivity": self.max_sensitivity,
            "projects": list(self.projects),
            "tenant": self.tenant,
        }
