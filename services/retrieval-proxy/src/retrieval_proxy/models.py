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


class Principal(BaseModel):
    """权限四元组：部门 / 角色 / 密级上限 / 项目。由网关按会话态注入，不由前端传入。"""

    user_id: str
    dept: list[str] = Field(default_factory=list)
    roles: list[str] = Field(default_factory=list)
    max_sensitivity: Sensitivity
    projects: list[str] = Field(default_factory=list)


class SearchRequest(BaseModel):
    query: str
    principal: Principal
    kb_scope: list[str] = Field(default_factory=list)
    top_k: int = 8
    score_threshold: float = 0.35
    mode: Mode = "full"


class Hit(BaseModel):
    chunk_id: str
    doc_id: str
    doc_name: str
    dept: str
    sensitivity: Sensitivity
    page: int | None = None
    score: float
    text: str


class SearchResponse(BaseModel):
    hits: list[Hit] = Field(default_factory=list)
    scope_desc: str = ""
    total: int = 0
