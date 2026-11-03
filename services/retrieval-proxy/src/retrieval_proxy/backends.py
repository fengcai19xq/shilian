"""检索后端 SPI（WK08 / contracts/platform.md §2）。

service 只依赖这里的 RetrievalBackend 协议，RAGFlow 与 WeKnora 各一个 adapter。
适配器负责「把权限条件翻译成后端方言并随请求下发」——红线 1 的落点。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

from .models import Principal


@dataclass(frozen=True)
class BackendQuery:
    """下发给后端的检索请求。

    权限相关的下推参数以两种形态并存：
    - `metadata_condition`：RAGFlow 方言（dept / sensitivity / project 的 IN 条件）
    - `kb_ids` / `tag_ids`：WeKnora 方言（密级+部门复合编码进 KB，project 进 tag）

    每个 adapter 只取自己需要的字段，其余忽略。这样同一份 service 逻辑可驱动两个后端。
    """

    question: str
    kb_scope: list[str]
    top_k: int
    similarity_threshold: float
    rerank: bool
    principal: Principal
    metadata_condition: dict[str, Any] = field(default_factory=dict)
    # WeKnora 侧：由 kb_encoding 依据 principal 推出的可见 KB 名与项目标签
    visible_kb_names: list[str] = field(default_factory=list)
    tag_names: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class BackendChunk:
    """后端返回的原始片段（未完成权限校验）。

    元数据字段缺失时一律为 None；调用方据此判「不通过」，宁缺勿错。
    """

    chunk_id: str
    doc_id: str
    doc_name: str
    dept: str
    sensitivity: str
    score: float
    text: str
    page: int | None = None
    project: str | None = None
    period: str | None = None
    scope: str | None = None
    copies: int | None = None
    stamped: bool | None = None


class BackendError(RuntimeError):
    """后端返回错误。错误信息不得包含任何文档内容或存在性信号。"""


@runtime_checkable
class RetrievalBackend(Protocol):
    """检索后端协议。"""

    def retrieve(self, query: BackendQuery) -> list[BackendChunk]:  # pragma: no cover - 协议声明
        ...
