package com.shilian.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shilian.ingest.audit.AuditEvent;
import com.shilian.ingest.audit.InMemoryAuditSink;
import com.shilian.ingest.chunk.Chunker;
import com.shilian.ingest.config.IngestProperties;
import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import com.shilian.ingest.parse.DocumentParser;
import com.shilian.ingest.ragflow.FakeRagflowClient;
import com.shilian.ingest.ragflow.RagflowClient;
import com.shilian.ingest.ragflow.RagflowChunk;
import com.shilian.ingest.ragflow.RagflowException;
import com.shilian.ingest.ragflow.ServerSideParseException;
import com.shilian.ingest.model.Sensitivity;
import com.shilian.ingest.store.InMemoryDocumentRepository;
import com.shilian.ingest.support.Samples;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/** 异步模式：POST 立即返回 uploaded，后台任务执行后才进入 parsing / indexed。 */
class IngestServiceTest {

    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final InMemoryDocumentRepository repo = new InMemoryDocumentRepository();
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final FakeRagflowClient ragflow = new FakeRagflowClient();
    private final IngestProperties props = new IngestProperties(new IngestProperties.Chunk(200, 20), true,
            "ragflow",
            new IngestProperties.Ragflow(null, null, "kb_default", Map.of("财务共享", "kb_finance")),
            new IngestProperties.WeKnora(null, null, null, 600, 5, null));
    private final IngestService service = new IngestService(repo, audit, ragflow, new DocumentParser(),
            new Chunker(200, 20), props, pending::add,
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));

    private IngestDocument upload(String dept) {
        return service.submit(new UploadCommand("u_10001", "报表.xlsx", Samples.read("sample.xlsx"), dept, "L2",
                null, "2023", null, null));
    }

    @Test
    void asyncSubmitReturnsUploadedThenIndexesInBackground() {
        IngestDocument doc = upload("财务共享");
        assertThat(doc.getStatus()).isEqualTo(DocStatus.UPLOADED);
        assertThat(ragflow.calls()).isEmpty();

        pending.remove().run();

        IngestDocument done = service.get(doc.getDocId());
        assertThat(done.getStatus()).isEqualTo(DocStatus.INDEXED);
        assertThat(done.getPageCount()).isEqualTo(2);
        assertThat(ragflow.datasets()).containsOnlyKeys("kb_finance");
        assertThat(done.getDatasetId()).isEqualTo(ragflow.datasets().get("kb_finance"));
        assertThat(audit.list(doc.getDocId())).extracting(AuditEvent::action)
                .containsExactly("uploaded", "parsing", "indexed");
    }

    @Test
    void unmappedDeptFallsBackToDefaultDatasetShared() {
        String a = upload("融资部").getDocId();
        String b = upload("融资部").getDocId();
        pending.forEach(Runnable::run);
        assertThat(ragflow.datasets()).containsOnlyKeys("kb_default");
        assertThat(service.get(a).getDatasetId()).isEqualTo(service.get(b).getDatasetId())
                .isEqualTo(ragflow.datasets().get("kb_default"));
    }

    // ---------------- WeKnora 通道（WK10）----------------

    /**
     * 服务端解析假通道：不本地解析、只记调用；可注入失败以验证回滚与原因文案。
     */
    private static final class FakeWeKnoraClient implements RagflowClient {
        final Map<String, String> datasetsByName = new java.util.LinkedHashMap<>();
        final List<String> calls = new java.util.ArrayList<>();
        final Map<String, Map<String, String>> metadataByDoc = new java.util.LinkedHashMap<>();
        ParseStats stats = new ParseStats(3, 15);
        RuntimeException failOnWait;
        String deletedDoc;

        @Override
        public boolean serverSideParsing() {
            return true;
        }

        @Override
        public String createDataset(String name) {
            calls.add("createDataset:" + name);
            return datasetsByName.computeIfAbsent(name, n -> "kb_" + (datasetsByName.size() + 1));
        }

        @Override
        public String uploadDocument(String datasetId, String fileName, byte[] content,
                Map<String, String> metadata) {
            calls.add("upload:" + datasetId + ":" + fileName);
            String id = "k_" + (metadataByDoc.size() + 1);
            metadataByDoc.put(id, metadata);
            return id;
        }

        @Override
        public void upsertChunks(String datasetId, String documentId, List<RagflowChunk> chunks) {
            throw new AssertionError("WeKnora 通道不应本地推块");
        }

        @Override
        public void deleteDocument(String datasetId, String documentId) {
            calls.add("delete:" + documentId);
            deletedDoc = documentId;
        }

        @Override
        public ParseStats waitForIndexed(String datasetId, String documentId) {
            calls.add("waitForIndexed:" + documentId);
            if (failOnWait != null) {
                throw failOnWait;
            }
            return stats;
        }
    }

    private IngestService weknoraService(FakeWeKnoraClient backend, String sink) {
        IngestProperties weknoraProps = new IngestProperties(new IngestProperties.Chunk(200, 20), true, sink,
                new IngestProperties.Ragflow(null, null, "kb_default", Map.of("财务共享", "kb_finance")),
                new IngestProperties.WeKnora("http://weknora.test", "k", "9001", 60, 1, "BAAI/bge-m3"));
        return new IngestService(repo, audit, backend, new DocumentParser(), new Chunker(200, 20), weknoraProps,
                pending::add, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void weknoraSinkNamesDatasetByTenantSensitivityAndDept() {
        FakeWeKnoraClient backend = new FakeWeKnoraClient();
        IngestService svc = weknoraService(backend, "weknora");

        IngestDocument doc = svc.submit(new UploadCommand("u_1", "notes.txt", Samples.read("notes.txt"),
                "融资部", "L2", "PRJ-DEMO-A", "2023", "合并", null, "acme"));
        pending.remove().run();

        IngestDocument done = svc.get(doc.getDocId());
        assertThat(done.getStatus()).isEqualTo(DocStatus.INDEXED);
        // KB 名 = 密级×部门复合编码，与检索侧 kb_encoding.py 逐字一致
        assertThat(backend.calls).containsExactly(
                "createDataset:sl-acme-L2-878d8d4490e8",
                "upload:kb_1:notes.txt",
                "waitForIndexed:k_1");
        assertThat(done.getDatasetId()).isEqualTo("kb_1");
        assertThat(done.getRagflowDocId()).isEqualTo("k_1");
    }

    @Test
    void weknoraSinkSkipsLocalParsingAndTakesServerStats() {
        FakeWeKnoraClient backend = new FakeWeKnoraClient();
        backend.stats = new RagflowClient.ParseStats(120, 310);
        IngestService svc = weknoraService(backend, "weknora");

        // .txt 本地解析器不支持，但服务端解析通道不依赖本地解析 → 应当成功
        IngestDocument doc = svc.submit(new UploadCommand("u_1", "notes.txt", Samples.read("notes.txt"),
                "融资部", "L1", null, null, null, null, "acme"));
        pending.remove().run();

        IngestDocument done = svc.get(doc.getDocId());
        assertThat(done.getStatus()).isEqualTo(DocStatus.INDEXED);
        assertThat(done.getPageCount()).isEqualTo(120);
        assertThat(done.getChunkCount()).isEqualTo(310);
        assertThat(audit.list(doc.getDocId())).extracting(AuditEvent::action)
                .containsExactly("uploaded", "parsing", "indexed");
    }

    @Test
    void weknoraParseTimeoutRollsBackAndGivesGenericReason() {
        FakeWeKnoraClient backend = new FakeWeKnoraClient();
        backend.failOnWait = new RagflowException("WeKnora 解析超时（最后状态: pending）");
        IngestService svc = weknoraService(backend, "weknora");

        IngestDocument doc = svc.submit(new UploadCommand("u_1", "报表.xlsx", Samples.read("sample.xlsx"),
                "融资部", "L2", null, null, null, null, "acme"));
        pending.remove().run();

        IngestDocument done = svc.get(doc.getDocId());
        assertThat(done.getStatus()).isEqualTo(DocStatus.FAILED);
        assertThat(done.getReason()).isEqualTo("推送 WeKnora 失败，已回滚本次写入，请稍后重试");
        // 原因不得含服务端原文/状态词
        assertThat(done.getReason()).doesNotContain("pending").doesNotContain("超时");
        assertThat(backend.deletedDoc).isEqualTo("k_1");
    }

    @Test
    void weknoraServerParseFailureUsesCategorisedReason() {
        FakeWeKnoraClient backend = new FakeWeKnoraClient();
        backend.failOnWait = new ServerSideParseException("server said 报表.xlsx password", "文档已加密，请解密后重新上传");
        IngestService svc = weknoraService(backend, "weknora");

        IngestDocument doc = svc.submit(new UploadCommand("u_1", "报表.xlsx", Samples.read("sample.xlsx"),
                "融资部", "L2", null, null, null, null, "acme"));
        pending.remove().run();

        IngestDocument done = svc.get(doc.getDocId());
        assertThat(done.getStatus()).isEqualTo(DocStatus.FAILED);
        assertThat(done.getReason()).isEqualTo("文档已加密，请解密后重新上传");
    }

    @Test
    void weknoraSinkStillHoldsL4WithoutAnyPush() {
        FakeWeKnoraClient backend = new FakeWeKnoraClient();
        IngestService svc = weknoraService(backend, "weknora");

        IngestDocument doc = svc.submit(new UploadCommand("u_1", "报表.xlsx", Samples.read("sample.xlsx"),
                "融资部", "L4", null, null, null, null, "acme"));

        assertThat(doc.getStatus()).isEqualTo(DocStatus.HELD_L4);
        assertThat(backend.calls).isEmpty();
        assertThat(pending).isEmpty();
    }

    @Test
    void weknoraSinkSendsProjectAndPeriodScopeInMetadata() {
        FakeWeKnoraClient backend = new FakeWeKnoraClient();
        IngestService svc = weknoraService(backend, "weknora");
        svc.submit(new UploadCommand("u_1", "报表.xlsx", Samples.read("sample.xlsx"),
                "融资部", "L3", "PRJ-DEMO-A", "2023", "合并", null, "acme"));
        pending.remove().run();

        assertThat(backend.metadataByDoc.get("k_1"))
                .containsEntry("dept", "融资部")
                .containsEntry("sensitivity", "L3")
                .containsEntry("project", "PRJ-DEMO-A")
                .containsEntry("period", "2023")
                .containsEntry("scope", "合并");
    }

    @Test
    void ragflowSinkIgnoresTenantForDatasetNaming() {
        // ragflow sink 下数据集名走部门映射，不因 tenant 传入而采用复合编码
        // （既有 service 字段即 ragflow sink + 内存通道）
        assertThat(service.datasetNameFor(new IngestDocument("d_x", "t", "f.pdf", "pdf", 1,
                "财务共享", Sensitivity.L2, null, null, null, "u_1", Instant.EPOCH, "acme")))
                .isEqualTo("kb_finance");
        assertThat(service.datasetNameFor(new IngestDocument("d_y", "t", "f.pdf", "pdf", 1,
                "融资部", Sensitivity.L4, null, null, null, "u_1", Instant.EPOCH, "acme")))
                .isEqualTo("kb_default");
    }
}
