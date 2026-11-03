"""WeKnora 检索 adapter（WK08 / contracts/weknora-integration.md）。

权限下推方式（与 RAGFlow 的 metadata_condition 并列，二选一按后端而定）：
- 密级 + 部门：复合编码进 KB 名，一次 `knowledge_base_ids` IN 表达两个 AND 维度
- 项目：`tag_ids`（含哨兵标签 project::__none__，对齐「未标注项目所有人可见」语义）

`knowledge_ids` 永久不下发：它与 KB 取并集，会绕开密级隔离（spike 陷阱 1）。

KB 名 / 标签名需先解析为 WeKnora 的 id；解析不出来的部分**直接不参与检索**，
宁少召回也不放过越权风险（与 ragflow.py 丢弃缺元数据片段同口径）。
"""

from __future__ import annotations

import os
from typing import Any

import httpx

from .backends import BackendChunk, BackendError, BackendQuery
from .kb_encoding import parse_kb_name, parse_project_tag, slug

# slug 冲突标记：同 slug 对应多个部门展示名时，该 KB 的结果一律丢弃
_CONFLICT = object()

# KB 名解析结果缓存上限：超出即清空，避免长驻进程无界增长
_CACHE_MAX = 512


class WeKnoraClient:
    """WeKnora `POST /api/v1/knowledge-search` 客户端。

    - 权限条件以 `knowledge_base_ids` + `tag_ids` 随检索请求一起下发（红线 1）
    - KB 名 / 标签名 → id 的解析结果缓存；解析不到的名称被剔除
    - 密级与部门直接从 KB 名反解，不依赖结果侧回查，避免把存在性泄露带进兜底逻辑
    - 密钥只从环境变量或构造参数读取，不在代码里硬编码
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        tenant_id: str = "",
        timeout: float = 30.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        headers = {"X-API-Key": api_key}
        if tenant_id:
            headers["X-Tenant-ID"] = str(tenant_id)
        self._http = httpx.Client(
            base_url=base_url.rstrip("/"), headers=headers, timeout=timeout, transport=transport
        )
        self._kb_ids: dict[str, str] = {}
        self._tag_ids: dict[str, str] = {}
        # 标签索引只拉一次：空空间（没有任何可授权 tag）也是确定的事实
        self._tags_loaded = False

    @classmethod
    def from_env(cls) -> WeKnoraClient:
        base_url = os.environ.get("WEKNORA_BASE_URL", "")
        api_key = os.environ.get("WEKNORA_API_KEY", "")
        if not base_url or not api_key:
            raise RuntimeError("缺少环境变量 WEKNORA_BASE_URL / WEKNORA_API_KEY")
        return cls(base_url, api_key, tenant_id=os.environ.get("WEKNORA_TENANT_ID", ""))

    # ---- 协议入口 ----

    def retrieve(self, query: BackendQuery) -> list[BackendChunk]:
        kb_ids, kb_meta = self._resolve_kbs(query.visible_kb_names, query.principal.dept)
        if not kb_ids:
            # 可见 KB 一个都解析不到：不发检索请求，也不给出任何存在性信号
            return []

        body: dict[str, Any] = {
            "query": query.question,
            "knowledge_base_ids": kb_ids,
            "match_count": query.top_k,
            "vector_threshold": query.similarity_threshold,
        }
        if not query.rerank:
            body["rerank"] = {"enabled": False}

        if query.tag_names:
            tag_ids = self._resolve_tags(query.tag_names)
            if not tag_ids:
                # 需要按项目收窄，但一个标签都解析不到 → 无法安全收窄，返回空
                return []
            body["tag_ids"] = tag_ids

        rows = self._search(body)
        chunks: list[BackendChunk] = []
        for raw in rows:
            kb_id = str(raw.get("knowledge_base_id", ""))
            meta = kb_meta.get(kb_id)
            if meta is None:
                # 结果落在我们没授权的 KB 上（理论上不该发生）：丢弃，宁缺勿错
                continue
            dept, sensitivity = meta
            chunk_id = str(raw.get("id", ""))
            if not chunk_id or not dept or not sensitivity:
                continue
            chunks.append(
                BackendChunk(
                    chunk_id=chunk_id,
                    doc_id=str(raw.get("knowledge_id", "")),
                    doc_name=str(raw.get("knowledge_title", "")),
                    dept=dept,
                    sensitivity=sensitivity,
                    score=float(raw.get("score", 0.0) or 0.0),
                    text=str(raw.get("content", "")),
                    page=_page_of(raw.get("source_locators")),
                    project=_project_of(raw.get("tag_ids") or raw.get("tags")),
                    copies=None,
                    stamped=None,
                )
            )
        return chunks[: query.top_k]

    # ---- 名称解析 ----

    def _resolve_kbs(
        self, names: list[str], depts: list[str]
    ) -> tuple[list[str], dict[str, tuple[str, str]]]:
        """KB 名 → id，并映射出 (dept 展示名, sensitivity)。解析不到的名称被剔除。

        KB 名里存的是 dept 的 slug（中文不可逆），故用 slug(dept) 反查 principal.dept
        的展示名；slug 不唯一的极端情况下退化为「无法确定部门」→ 剔除该 KB，宁缺勿错。
        """
        if not self._kb_ids:
            listed = self._get("/api/v1/knowledge-bases")
            rows = listed.get("data", []) if isinstance(listed, dict) else listed
            for row in rows or []:
                name, rid = str(row.get("name", "")), str(row.get("id", ""))
                if name and rid:
                    self._kb_ids[name] = rid

        slug_to_dept: dict[str, str] = {}
        for dept in depts:
            s = slug(dept)
            # 同 slug 冲突时保留首个并标记冲突：冲突的 KB 将被剔除
            if s in slug_to_dept and slug_to_dept[s] != dept:
                slug_to_dept[s] = _CONFLICT
            else:
                slug_to_dept[s] = dept

        ids: list[str] = []
        meta: dict[str, tuple[str, str]] = {}
        for name in names:
            rid = self._kb_ids.get(name)
            if not rid:
                continue
            try:
                _, sensitivity, dept_slug = parse_kb_name(name)
            except Exception:
                continue
            dept = slug_to_dept.get(dept_slug, _CONFLICT)
            if dept is _CONFLICT or dept is None:
                # 部门反查不确定：丢弃该 KB 的结果，避免 dept 字段写错导致兜底校验误放行
                continue
            ids.append(rid)
            meta[rid] = (dept, sensitivity)
        return ids, meta

    def _resolve_tags(self, names: list[str]) -> list[str]:
        if not self._tags_loaded:
            self._load_tag_index()
        return [self._tag_ids[n] for n in names if n in self._tag_ids]

    def _load_tag_index(self) -> None:
        """遍历已解析出的 KB，收集 tag 名 → id。

        tag 是 KB 级资源，无全局列表接口；这里按可见 KB 聚合并缓存。
        用 _tags_loaded 标记「已拉取过」而非「非空」：空标签空间同样是确定事实，
        否则每次检索都会重拉一遍索引。
        """
        for kb_id in self._kb_ids.values():
            data = self._get(f"/api/v1/knowledge-bases/{kb_id}/tags", ok_on_error=True)
            rows = data.get("data", []) if isinstance(data, dict) else data
            for row in rows or []:
                name, tid = str(row.get("name", "")), str(row.get("id", ""))
                if name and tid:
                    self._tag_ids[name] = tid
        self._tags_loaded = True
        if len(self._tag_ids) > _CACHE_MAX:
            self._tag_ids = dict(list(self._tag_ids.items())[-_CACHE_MAX:])

    # ---- 底层 ----

    def _search(self, body: dict[str, Any]) -> list[dict[str, Any]]:
        resp = self._http.post("/api/v1/knowledge-search", json=body)
        data = _unwrap(resp)
        if isinstance(data, dict):
            rows = data.get("data", [])
        else:
            rows = data
        return rows if isinstance(rows, list) else []

    def _get(self, path: str, params: dict[str, Any] | None = None,
             *, ok_on_error: bool = False) -> Any:
        resp = self._http.get(path, params=params or {})
        if ok_on_error and resp.status_code >= 400:
            return {}
        return _unwrap(resp)


def _unwrap(resp: httpx.Response) -> Any:
    if resp.status_code >= 400:
        # 只带状态码：不透出响应体，避免把文档内容或存在性信号带回上层与日志
        raise BackendError(f"WeKnora HTTP {resp.status_code}")
    payload = resp.json()
    if isinstance(payload, dict) and payload.get("success") is False:
        raise BackendError(f"WeKnora error {payload.get('error', {}).get('code')}")
    return payload.get("data", payload) if isinstance(payload, dict) else payload


def _page_of(locators: Any) -> int | None:
    """SearchResult.source_locators：pdf 类型带 page（从 1 起）。取首个有效页码。"""
    if not isinstance(locators, list):
        return None
    for loc in locators:
        if isinstance(loc, dict):
            page = loc.get("page")
            if isinstance(page, int) and page > 0:
                return page
    return None


def _project_of(tags: Any) -> str | None:
    """从结果的标签里反解 project 原值；哨兵标签视为「未标注项目」→ None。

    必须走 parse_project_tag 做可逆解码：标签名里存的是定长 hex 编码，
    直接把编码串当项目值会与文档元数据永远不相等，导致兜底校验误判越权、
    把已授权文档静默丢弃。
    """
    if not isinstance(tags, list):
        return None
    for tag in tags:
        name = tag.get("name") if isinstance(tag, dict) else (tag if isinstance(tag, str) else None)
        if not name:
            continue
        parsed = parse_project_tag(str(name))
        if parsed is None:
            continue
        return parsed or None  # 哨兵（空串）→ None，与 RAGFlow 版 chunk.project is None 对齐
    return None
