package com.shilian.ingest.service;

import com.shilian.ingest.audit.AuditEvent;
import com.shilian.ingest.audit.AuditSink;
import com.shilian.ingest.chunk.Chunk;
import com.shilian.ingest.chunk.Chunker;
import com.shilian.ingest.config.IngestProperties;
import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import com.shilian.ingest.model.MetadataKeys;
import com.shilian.ingest.model.Sensitivity;
import com.shilian.ingest.parse.DocumentParseException;
import com.shilian.ingest.parse.DocumentParser;
import com.shilian.ingest.parse.FileType;
import com.shilian.ingest.parse.ParsedDocument;
import com.shilian.ingest.ragflow.RagflowChunk;
import com.shilian.ingest.ragflow.RagflowClient;
import com.shilian.ingest.ragflow.RagflowException;
import com.shilian.ingest.store.DocumentRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 入库流水线：登记 → 解析 → 切块 → 推送 RAGFlow。
 * 状态机 uploaded → parsing → indexed | failed；L4 文档 uploaded → held_l4，不解析、不推送（L4 不出 VPC）。
 * 每次状态变化写一条审计事件。
 */
@Service
public class IngestService {

    public static final String L4_HELD_REASON = "L4 不出 VPC：仅登记，不解析、不推送 RAGFlow";
    static final String RAGFLOW_FAILED_REASON = "推送 RAGFlow 失败，已回滚本次写入，请稍后重试";
    static final String INTERNAL_FAILED_REASON = "内部错误：解析或推送过程中出现未预期异常，请联系管理员";

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final DocumentRepository repo;
    private final AuditSink audit;
    private final RagflowClient ragflow;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final IngestProperties.Ragflow ragflowProps;
    private final Executor executor;
    private final Clock clock;

    public IngestService(DocumentRepository repo, AuditSink audit, RagflowClient ragflow, DocumentParser parser,
            Chunker chunker, IngestProperties props, @Qualifier("ingestExecutor") Executor executor, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.ragflow = ragflow;
        this.parser = parser;
        this.chunker = chunker;
        this.ragflowProps = props.ragflow();
        this.executor = executor;
        this.clock = clock;
    }

    /** 登记文档并触发解析；返回登记后的快照（同步模式下即为终态）。参数非法抛 IllegalArgumentException。 */
    public IngestDocument submit(UploadCommand cmd) {
        String dept = required(cmd.dept(), "缺少必填字段: dept");
        Sensitivity sensitivity = Sensitivity.parse(cmd.sensitivity());
        if (cmd.content() == null) {
            throw new IllegalArgumentException("缺少上传文件: file");
        }
        if (cmd.content().length == 0) {
            throw new IllegalArgumentException("上传文件为空: file");
        }
        String actor = required(cmd.actor(), "缺少请求头: X-User-Id");
        String fileName = blankToNull(cmd.fileName()) == null ? "unnamed" : cmd.fileName().strip();
        String title = blankToNull(cmd.title()) == null ? fileName : cmd.title().strip();

        IngestDocument doc = new IngestDocument(repo.nextId(), title, fileName, FileType.extensionOf(fileName),
                cmd.content().length, dept, sensitivity, blankToNull(cmd.project()), blankToNull(cmd.period()),
                blankToNull(cmd.scope()), actor, clock.instant());
        repo.save(doc);
        writeAudit(actor, doc, null);

        if (sensitivity == Sensitivity.L4) {
            transit(doc.getDocId(), DocStatus.HELD_L4, L4_HELD_REASON, actor);
        } else {
            byte[] content = cmd.content();
            executor.execute(() -> process(doc.getDocId(), content, actor));
        }
        return get(doc.getDocId());
    }

    public IngestDocument get(String docId) {
        return repo.findById(docId).orElseThrow(() -> new NotFoundException("文档不存在: " + docId));
    }

    public List<IngestDocument> list(String dept, String status) {
        DocStatus st = blankToNull(status) == null ? null : DocStatus.parse(status.strip());
        return repo.find(blankToNull(dept), st);
    }

    void process(String docId, byte[] content, String actor) {
        try {
            IngestDocument doc = transit(docId, DocStatus.PARSING, null, actor);
            ParsedDocument parsed = parser.parse(doc.getFileName(), content);
            List<Chunk> chunks = chunker.split(parsed.pages());
            if (chunks.isEmpty()) {
                throw new DocumentParseException("未抽取到任何文本（可能是扫描件、纯图片或空文档，需 OCR 后再上传）");
            }
            pushToRagflow(doc, content, chunks);
            IngestDocument latest = get(docId);
            latest.setParseResult(parsed.pageCount(), chunks.size());
            repo.save(latest);
            transit(docId, DocStatus.INDEXED, null, actor);
        } catch (DocumentParseException e) {
            fail(docId, e.getMessage(), actor);
        } catch (RagflowException e) {
            log.warn("ragflow push failed: doc={} error={}", docId, e.getMessage());
            fail(docId, RAGFLOW_FAILED_REASON, actor);
        } catch (RuntimeException e) {
            log.error("ingest failed unexpectedly: doc={}", docId, e);
            fail(docId, INTERNAL_FAILED_REASON, actor);
        }
    }

    private void pushToRagflow(IngestDocument doc, byte[] content, List<Chunk> chunks) {
        String datasetName = ragflowProps.datasetFor(doc.getDept());
        String datasetId = ragflow.createDataset(datasetName);
        Map<String, String> metadata = metadataOf(doc);
        String rfDocId = ragflow.uploadDocument(datasetId, doc.getFileName(), content, metadata);
        try {
            List<RagflowChunk> payload = chunks.stream()
                    .map(c -> new RagflowChunk(chunkId(doc.getDocId(), c.index()), c.text(), c.page(), metadata))
                    .toList();
            ragflow.upsertChunks(datasetId, rfDocId, payload);
        } catch (RuntimeException e) {
            try {
                ragflow.deleteDocument(datasetId, rfDocId);
            } catch (RuntimeException cleanup) {
                log.warn("ragflow rollback failed: doc={} rf_doc={}", doc.getDocId(), rfDocId);
            }
            throw e;
        }
        IngestDocument latest = get(doc.getDocId());
        latest.setRagflowRefs(datasetId, rfDocId);
        repo.save(latest);
    }

    /** 元数据键名与 contracts/retrieval.md 一致；可选字段为空时不写入。 */
    static Map<String, String> metadataOf(IngestDocument doc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(MetadataKeys.DEPT, doc.getDept());
        m.put(MetadataKeys.SENSITIVITY, doc.getSensitivity().name());
        putIfPresent(m, MetadataKeys.PROJECT, doc.getProject());
        putIfPresent(m, MetadataKeys.PERIOD, doc.getPeriod());
        putIfPresent(m, MetadataKeys.SCOPE, doc.getScope());
        return Map.copyOf(m);
    }

    static String chunkId(String docId, int index) {
        return String.format("%s_c%04d", docId, index);
    }

    private void fail(String docId, String reason, String actor) {
        IngestDocument doc = get(docId);
        if (doc.getStatus().canTransitTo(DocStatus.FAILED)) {
            transit(docId, DocStatus.FAILED, reason, actor);
        }
    }

    private synchronized IngestDocument transit(String docId, DocStatus next, String reason, String actor) {
        IngestDocument doc = get(docId);
        DocStatus from = doc.getStatus();
        if (!from.canTransitTo(next)) {
            throw new IllegalStateException("非法状态迁移: " + from.wire() + " -> " + next.wire());
        }
        doc.setStatus(next, reason, clock.instant());
        repo.save(doc);
        writeAudit(actor, doc, from);
        return doc;
    }

    private void writeAudit(String actor, IngestDocument doc, DocStatus from) {
        audit.record(new AuditEvent(actor, doc.getDocId(), doc.getStatus().wire(), from, doc.getSensitivity(),
                doc.getReason(), doc.getUpdatedAt()));
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static String required(String value, String message) {
        String v = blankToNull(value);
        if (v == null) {
            throw new IllegalArgumentException(message);
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
