"""retrieval-proxy：权限过滤与检索转发。"""

from .audit import AuditEvent, AuditSink, InMemoryAuditSink
from .models import Hit, Principal, SearchRequest, SearchResponse
from .permissions import build_metadata_condition, expand_sensitivity
from .ragflow import RagflowChunk, RagflowClient, RagflowQuery
from .service import RetrievalService

__all__ = [
    "AuditEvent",
    "AuditSink",
    "Hit",
    "InMemoryAuditSink",
    "Principal",
    "RagflowChunk",
    "RagflowClient",
    "RagflowQuery",
    "RetrievalService",
    "SearchRequest",
    "SearchResponse",
    "build_metadata_condition",
    "expand_sensitivity",
]
