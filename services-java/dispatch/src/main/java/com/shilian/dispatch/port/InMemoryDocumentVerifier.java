package com.shilian.dispatch.port;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 文档库 fake：register 后的 doc_id 才视为存在。 */
public class InMemoryDocumentVerifier implements DocumentVerifier {

    private final Map<String, DocumentMeta> docs = new ConcurrentHashMap<>();

    public InMemoryDocumentVerifier register(String docId, String stdType) {
        docs.put(docId, new DocumentMeta(docId, stdType));
        return this;
    }

    @Override
    public Optional<DocumentMeta> lookup(String docId) {
        return Optional.ofNullable(docs.get(docId));
    }
}
