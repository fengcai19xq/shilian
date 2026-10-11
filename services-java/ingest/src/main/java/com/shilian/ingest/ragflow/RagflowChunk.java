package com.shilian.ingest.ragflow;

import java.util.Map;

/** 推送到 RAGFlow 的切块；metadata 键名与 contracts/retrieval.md 一致。 */
public record RagflowChunk(String chunkId, String content, int page, Map<String, String> metadata) {}
