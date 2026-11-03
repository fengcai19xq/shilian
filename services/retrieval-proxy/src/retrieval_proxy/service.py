"""检索服务：权限翻译 -> 检索前过滤 -> 结果整形 -> 审计。

后端通过 RetrievalBackend SPI 注入（RAGFlow / WeKnora 各一 adapter），
本文件与后端方言无关；权限的下推形态由 adapter 各自负责。
"""

from __future__ import annotations

from .audit import AuditEvent, AuditSink
from .backends import BackendChunk, BackendQuery, RetrievalBackend
from .models import PROBE_TOP_K, Hit, Principal, SearchRequest, SearchResponse
from .permissions import build_backend_scope, build_metadata_condition, expand_sensitivity, has_any_access


class RetrievalService:
    """对外唯一入口；检索后端与审计写出都由外部注入。"""

    def __init__(self, backend: RetrievalBackend, audit_sink: AuditSink) -> None:
        self._backend = backend
        self._audit = audit_sink

    def search(self, request: SearchRequest) -> SearchResponse:
        principal = request.principal
        scope_desc = self._scope_desc(principal)

        # 完全无权时直接返回空，既不发检索请求，也不返回任何可推断文档存在性的信息
        if not has_any_access(principal, request.kb_scope):
            self._write_audit(request, hit_doc_ids=[])
            return SearchResponse(hits=[], scope_desc=scope_desc, total=0)

        is_probe = request.mode == "probe"
        # 两套下推方言同时构造：RAGFlow 用 metadata_condition，WeKnora 用 KB 名 + tag 名，
        # adapter 各取所需，多余的字段被忽略
        scope = build_backend_scope(principal)
        query = BackendQuery(
            question=request.query,
            kb_scope=list(request.kb_scope),
            top_k=PROBE_TOP_K if is_probe else request.top_k,
            similarity_threshold=request.score_threshold,
            rerank=not is_probe,
            principal=principal,
            # 权限条件随检索请求一起下发，过滤发生在后端内部，不做后过滤
            metadata_condition=build_metadata_condition(principal),
            visible_kb_names=scope.knowledge_base_ids,
            tag_names=scope.tag_names,
        )
        chunks = self._backend.retrieve(query)

        # 兜底校验：即使下游忽略了下推条件，越权片段也不会进入返回体。
        # 这是防御性冗余，不是主过滤手段——主过滤始终在检索请求里完成。
        allowed = [c for c in chunks if self._is_allowed(c, principal)]
        if is_probe:
            # probe 只用于路由判定，只返回最高分片段
            allowed = sorted(allowed, key=lambda c: c.score, reverse=True)[:1]

        hits = [self._to_hit(c) for c in allowed]
        self._write_audit(request, hit_doc_ids=[h.doc_id for h in hits])
        return SearchResponse(hits=hits, scope_desc=scope_desc, total=len(hits))

    @staticmethod
    def _is_allowed(chunk: BackendChunk, principal: Principal) -> bool:
        if chunk.dept not in principal.dept:
            return False
        if chunk.sensitivity not in expand_sensitivity(principal.max_sensitivity):
            return False
        if principal.projects and chunk.project is not None:
            return chunk.project in principal.projects
        return True

    @staticmethod
    def _to_hit(chunk: BackendChunk) -> Hit:
        return Hit(
            chunk_id=chunk.chunk_id,
            doc_id=chunk.doc_id,
            doc_name=chunk.doc_name,
            dept=chunk.dept,
            sensitivity=chunk.sensitivity,  # type: ignore[arg-type]
            page=chunk.page,
            score=chunk.score,
            text=chunk.text,
            period=chunk.period,
            scope=chunk.scope,
            copies=chunk.copies,
            stamped=chunk.stamped,
        )

    @staticmethod
    def _scope_desc(principal: Principal) -> str:
        if not principal.dept:
            return "（无可检索范围）"
        return f"{' / '.join(principal.dept)}（你的权限内）"

    def _write_audit(self, request: SearchRequest, hit_doc_ids: list[str]) -> None:
        self._audit.write(
            AuditEvent(
                who=request.principal.user_id,
                query=request.query,
                scope=list(request.kb_scope),
                hit_doc_ids=hit_doc_ids,
                mode=request.mode,
            )
        )
