"""契约 `contracts/retrieval.md` 对应的请求 / 返回模型。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

# 密级由低到高，顺序即包含关系：L3 用户可见 L1/L2/L3
SENSITIVITY_ORDER: tuple[str, ...] = ("L1", "L2", "L3", "L4")

Sensitivity = Literal["L1", "L2", "L3", "L4"]
Mode = Literal["probe", "full"]

# probe 模式的固定参数：top_k 压到 3、不做 rerank
PROBE_TOP_K = 3

# 单租户部署的默认租户标识（contracts/platform.md §1.1 只增不改）
DEFAULT_TENANT = "default"


class Principal(BaseModel):
    """权限四元组 + 租户：部门 / 角色 / 密级上限 / 项目 / tenant。

    由网关按会话态注入，不由前端传入。tenant 缺省为单租户默认值，
    既有调用方与测试无需改动。
    """

    user_id: str
    dept: list[str] = Field(default_factory=list)
    roles: list[str] = Field(default_factory=list)
    max_sensitivity: Sensitivity
    projects: list[str] = Field(default_factory=list)
    tenant: str = DEFAULT_TENANT


class SearchRequest(BaseModel):
    query: str
    principal: Principal
    kb_scope: list[str] = Field(default_factory=list)
    top_k: int = 8
    score_threshold: float = 0.35
    mode: Mode = "full"


class Hit(BaseModel):
    """检索命中片段。

    period / scope / copies / stamped 为 contracts/retrieval.md「补充（2026-10）」
    新增的可选元数据，供 matcher 硬约束使用；后端未提供时一律为 None，
    matcher 侧据此判「不通过」。
    """

    chunk_id: str
    doc_id: str
    doc_name: str
    dept: str
    sensitivity: Sensitivity
    page: int | None = None
    score: float
    text: str
    period: str | None = None
    scope: str | None = None
    copies: int | None = None
    stamped: bool | None = None


class SearchResponse(BaseModel):
    hits: list[Hit] = Field(default_factory=list)
    scope_desc: str = ""
    total: int = 0
