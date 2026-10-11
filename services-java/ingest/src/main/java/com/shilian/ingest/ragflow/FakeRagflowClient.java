package com.shilian.ingest.ragflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 内存版 RAGFlow，用于测试与本地开发；可注入一次性故障以验证失败路径。 */
public class FakeRagflowClient implements RagflowClient {

    /** 已上传文档的快照（不保存原始字节，只记大小）。 */
    public record StoredDocument(String datasetId, String fileName, long sizeBytes,
            Map<String, String> metadata, Map<String, RagflowChunk> chunks) {}

    private final Map<String, String> datasetsByName = new ConcurrentHashMap<>();
    private final Map<String, StoredDocument> documents = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile String failNextUpload;
    private volatile String failNextUpsert;

    @Override
    public String createDataset(String name) {
        calls.add("createDataset:" + name);
        return datasetsByName.computeIfAbsent(name, n -> "ds_" + seq.incrementAndGet());
    }

    @Override
    public String uploadDocument(String datasetId, String fileName, byte[] content, Map<String, String> metadata) {
        calls.add("uploadDocument:" + datasetId + ":" + fileName);
        String fail = failNextUpload;
        if (fail != null) {
            failNextUpload = null;
            throw new RagflowException(fail);
        }
        requireDataset(datasetId);
        String id = "rf_doc_" + seq.incrementAndGet();
        documents.put(id, new StoredDocument(datasetId, fileName, content.length,
                Map.copyOf(metadata), new ConcurrentHashMap<>()));
        return id;
    }

    @Override
    public void upsertChunks(String datasetId, String documentId, List<RagflowChunk> chunks) {
        calls.add("upsertChunks:" + documentId + ":" + chunks.size());
        String fail = failNextUpsert;
        if (fail != null) {
            failNextUpsert = null;
            throw new RagflowException(fail);
        }
        StoredDocument doc = documents.get(documentId);
        if (doc == null || !doc.datasetId().equals(datasetId)) {
            throw new RagflowException("document not found: " + documentId);
        }
        for (RagflowChunk c : chunks) {
            doc.chunks().put(c.chunkId(), c);
        }
    }

    @Override
    public void deleteDocument(String datasetId, String documentId) {
        calls.add("deleteDocument:" + documentId);
        documents.remove(documentId);
    }

    private void requireDataset(String datasetId) {
        if (!datasetsByName.containsValue(datasetId)) {
            throw new RagflowException("dataset not found: " + datasetId);
        }
    }

    public void failNextUpload(String message) {
        this.failNextUpload = message;
    }

    public void failNextUpsert(String message) {
        this.failNextUpsert = message;
    }

    public StoredDocument document(String documentId) {
        return documents.get(documentId);
    }

    public Map<String, StoredDocument> documents() {
        return Map.copyOf(documents);
    }

    /** 按 chunkId 排序的切块列表。 */
    public List<RagflowChunk> chunks(String documentId) {
        StoredDocument doc = documents.get(documentId);
        if (doc == null) {
            return List.of();
        }
        List<RagflowChunk> list = new ArrayList<>(new LinkedHashMap<>(doc.chunks()).values());
        list.sort((a, b) -> a.chunkId().compareTo(b.chunkId()));
        return list;
    }

    public Map<String, String> datasets() {
        return Map.copyOf(datasetsByName);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public void clear() {
        datasetsByName.clear();
        documents.clear();
        calls.clear();
        failNextUpload = null;
        failNextUpsert = null;
    }
}
