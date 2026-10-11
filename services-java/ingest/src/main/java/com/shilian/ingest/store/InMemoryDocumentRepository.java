package com.shilian.ingest.store;

import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<String, IngestDocument> docs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public String nextId() {
        return String.format("d_%06d", seq.incrementAndGet());
    }

    @Override
    public void save(IngestDocument doc) {
        docs.put(doc.getDocId(), doc.copy());
    }

    @Override
    public Optional<IngestDocument> findById(String docId) {
        return Optional.ofNullable(docs.get(docId)).map(IngestDocument::copy);
    }

    @Override
    public List<IngestDocument> find(String dept, DocStatus status) {
        return docs.values().stream()
                .filter(d -> dept == null || dept.equals(d.getDept()))
                .filter(d -> status == null || status == d.getStatus())
                .sorted((a, b) -> a.getDocId().compareTo(b.getDocId()))
                .map(IngestDocument::copy)
                .toList();
    }

    public void clear() {
        docs.clear();
        seq.set(0);
    }
}
