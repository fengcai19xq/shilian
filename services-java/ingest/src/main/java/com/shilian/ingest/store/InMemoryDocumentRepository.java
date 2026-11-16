package com.shilian.ingest.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import com.shilian.ingest.model.Sensitivity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文档登记存储。
 *
 * <p>默认纯内存；构造时传入文件路径则把登记记录以 JSON 快照落盘，进程重启后可恢复。
 * 只持久化登记元数据与状态，不保存原始文件字节（原始文件仍由 RAGFlow / WeKnora 持有）。
 * 写入采用“临时文件 + 原子替换”，避免进程中断留下半个 JSON。
 */
public class InMemoryDocumentRepository implements DocumentRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Snapshot>> SNAPSHOT_TYPE = new TypeReference<>() {};

    private final Map<String, IngestDocument> docs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Path snapshotPath;

    public InMemoryDocumentRepository() {
        this(null);
    }

    public InMemoryDocumentRepository(Path snapshotPath) {
        this.snapshotPath = snapshotPath;
        if (snapshotPath != null) {
            load();
        }
    }

    @Override
    public synchronized String nextId() {
        return String.format("d_%06d", seq.incrementAndGet());
    }

    @Override
    public synchronized void save(IngestDocument doc) {
        docs.put(doc.getDocId(), doc.copy());
        persistLocked();
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

    public synchronized void clear() {
        docs.clear();
        seq.set(0);
        persistLocked();
    }

    private void load() {
        if (!Files.exists(snapshotPath)) {
            return;
        }
        try {
            List<Snapshot> snapshots = JSON.readValue(Files.readString(snapshotPath), SNAPSHOT_TYPE);
            long max = 0;
            boolean interrupted = false;
            for (Snapshot s : snapshots) {
                IngestDocument doc = s.toDocument();
                // uploaded/parsing 依赖进程内任务线程；重启后没有线程会继续推进，
                // 标记 failed 并给出明确原因，避免永远停在中间态。
                if (doc.getStatus() == DocStatus.UPLOADED || doc.getStatus() == DocStatus.PARSING) {
                    doc.setStatus(DocStatus.FAILED, "服务重启导致入库中断", Instant.now());
                    interrupted = true;
                }
                docs.put(doc.getDocId(), doc);
                max = Math.max(max, numericId(doc.getDocId()));
            }
            seq.set(max);
            if (interrupted) {
                persistLocked();
            }
        } catch (IOException | RuntimeException e) {
            // 登记是权限与审计的权威来源，损坏时宁可启动失败也不静默清空
            throw new IllegalStateException("读取文档登记失败：" + snapshotPath, e);
        }
    }

    private void persistLocked() {
        if (snapshotPath == null) {
            return;
        }
        try {
            Path parent = snapshotPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<Snapshot> snapshots = docs.values().stream()
                    .sorted((a, b) -> a.getDocId().compareTo(b.getDocId()))
                    .map(Snapshot::of)
                    .toList();
            Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(snapshots));
            try {
                Files.move(tmp, snapshotPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入文档登记失败：" + snapshotPath, e);
        }
    }

    private static long numericId(String docId) {
        if (docId == null || !docId.startsWith("d_")) {
            return 0;
        }
        try {
            return Long.parseLong(docId.substring(2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 持久化快照；字段与 IngestDocument 一一对应。 */
    record Snapshot(
            String docId,
            String title,
            String fileName,
            String fileType,
            long sizeBytes,
            String dept,
            String sensitivity,
            String project,
            String period,
            String scope,
            String uploadedBy,
            String createdAt,
            String tenant,
            Integer copies,
            Boolean stamped,
            String stdType,
            String storageUri,
            String status,
            String reason,
            int pageCount,
            int chunkCount,
            String datasetId,
            String ragflowDocId,
            String updatedAt) {

        static Snapshot of(IngestDocument d) {
            return new Snapshot(d.getDocId(), d.getTitle(), d.getFileName(), d.getFileType(), d.getSizeBytes(),
                    d.getDept(), d.getSensitivity().name(), d.getProject(), d.getPeriod(), d.getScope(),
                    d.getUploadedBy(), d.getCreatedAt().toString(), d.getTenant(), d.getCopies(), d.getStamped(),
                    d.getStdType(), d.getStorageUri(), d.getStatus().wire(), d.getReason(), d.getPageCount(),
                    d.getChunkCount(),
                    d.getDatasetId(), d.getRagflowDocId(),
                    d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString());
        }

        IngestDocument toDocument() {
            IngestDocument doc = new IngestDocument(docId, title, fileName, fileType, sizeBytes, dept,
                    Sensitivity.valueOf(sensitivity), project, period, scope, uploadedBy,
                    Instant.parse(createdAt), tenant, copies, stamped, stdType, storageUri);
            Instant updated = updatedAt == null ? null : Instant.parse(updatedAt);
            doc.setStatus(DocStatus.parse(status), reason, updated);
            doc.setParseResult(pageCount, chunkCount);
            doc.setRagflowRefs(datasetId, ragflowDocId);
            return doc;
        }
    }
}
