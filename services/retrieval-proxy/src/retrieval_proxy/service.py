"""检索服务：权限翻译 -> 检索前过滤 -> 结果整形 -> 审计。"""

from __future__ import annotations

from .audit import AuditEvent, AuditSink
from .models import PROBE_TOP_K, Hit, Principal, SearchRequest, SearchResponse
from .permissions import build_metadata_condition, expand_sensitivity, has_any_access
from .ragflow import RagflowChunk, RagflowClient, RagflowQuery


class RetrievalService:
    """对外唯一入口；RAGFlow 客户端与审计写出都由外部注入。"""

    def __init__(self, client: RagflowClient, audit_sink: AuditSink) -> None:
        self._client = client
        self._audit = audit_sink

    def search(self, request: SearchRequest) -> SearchResponse:
        principal = request.principal
        scope_desc = self._scope_desc(principal)

        # 完全无权时直接返回空，既不发检索请求，也不返回任何可推断文档存在性的信息
        if not has_any_access(principal, request.kb_scope):
            self._write_audit(request, hit_doc_ids=[])
            return SearchResponse(hits=[], scope_desc=scope_desc, total=0)

        is_probe = request.mode == "probe"
        query = RagflowQuery(
            question=request.query,
            kb_ids=list(request.kb_scope),
            top_k=PROBE_TOP_K if is_probe else request.top_k,
            similarity_threshold=request.score_threshold,
            rerank=not is_probe,
            # 权限条件随检索请求一起下发，过滤发生在 RAGFlow 侧，不做后过滤
            metadata_condition=build_metadata_condition(principal),
        )
        chunks = self._client.retrieve(query)

        # 兜底校验：即使下游忽略了 metadata_condition，越权片段也不会进入返回体。
        # 这是防御性冗余，不是主过滤手段——主过滤始终在检索请求里完成。
        allowed = [c for c in chunks if self._is_allowed(c, principal)]
        if is_probe:
            # probe 只用于路由判定，只返回最高分片段
            allowed = sorted(allowed, key=lambda c: c.score, reverse=True)[:1]

        hits = [self._to_hit(c) for c in allowed]
        self._write_audit(request, hit_doc_ids=[h.doc_id for h in hits])
        return SearchResponse(hits=hits, scope_desc=scope_desc, total=len(hits))

    @staticmethod
    def _is_allowed(chunk: RagflowChunk, principal: Principal) -> bool:
        if chunk.dept not in principal.dept:
            return False
        if chunk.sensitivity not in expand_sensitivity(principal.max_sensitivity):
            return False
        if principal.projects and chunk.project is not None:
            return chunk.project in principal.projects
        return True

    @staticmethod
    def _to_hit(chunk: RagflowChunk) -> Hit:
        return Hit(
            chunk_id=chunk.chunk_id,
            doc_id=chunk.doc_id,
            doc_name=chunk.doc_name,
            dept=chunk.dept,
            sensitivity=chunk.sensitivity,  # type: ignore[arg-type]
            page=chunk.page,
            score=chunk.score,
            text=chunk.text,
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
