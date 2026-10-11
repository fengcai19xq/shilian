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
import com.shilian.ingest.store.InMemoryDocumentRepository;
import com.shilian.ingest.support.Samples;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
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
            new IngestProperties.Ragflow(null, null, "kb_default", Map.of("财务共享", "kb_finance")));
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
}
