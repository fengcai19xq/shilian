package com.shilian.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import com.shilian.ingest.model.Sensitivity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryDocumentRepositoryTest {

    private static IngestDocument doc(String id) {
        IngestDocument d = new IngestDocument(id, "审计报告", "a.pdf", "pdf", 1024, "财务共享",
                Sensitivity.L3, "PRJ-1", "2023", "合并", "u_1",
                Instant.parse("2026-11-10T00:00:00Z"), "default", 3, true, "AUDIT_REPORT");
        d.setStatus(DocStatus.INDEXED, null, Instant.parse("2026-11-10T00:00:05Z"));
        d.setParseResult(12, 34);
        d.setRagflowRefs("ds_1", "rf_1");
        return d;
    }

    @Test
    void 重启后可从快照恢复登记与状态(@TempDir Path dir) {
        Path file = dir.resolve("documents.json");
        InMemoryDocumentRepository first = new InMemoryDocumentRepository(file);
        String id = first.nextId();
        first.save(doc(id));
        assertThat(Files.exists(file)).isTrue();

        InMemoryDocumentRepository second = new InMemoryDocumentRepository(file);
        IngestDocument restored = second.findById(id).orElseThrow();
        assertThat(restored.getTitle()).isEqualTo("审计报告");
        assertThat(restored.getStatus()).isEqualTo(DocStatus.INDEXED);
        assertThat(restored.getPageCount()).isEqualTo(12);
        assertThat(restored.getChunkCount()).isEqualTo(34);
        assertThat(restored.getDatasetId()).isEqualTo("ds_1");
        assertThat(restored.getRagflowDocId()).isEqualTo("rf_1");
        assertThat(restored.getCopies()).isEqualTo(3);
        assertThat(restored.getStamped()).isTrue();
        assertThat(restored.getStdType()).isEqualTo("AUDIT_REPORT");
        // 序号从已有登记推导，不会回退覆盖
        assertThat(second.nextId()).isEqualTo("d_000002");
    }

    @Test
    void 重启时未完成文档标记失败(@TempDir Path dir) {
        Path file = dir.resolve("documents.json");
        InMemoryDocumentRepository first = new InMemoryDocumentRepository(file);
        String id = first.nextId();
        IngestDocument d = doc(id);
        d.setStatus(DocStatus.PARSING, null, Instant.parse("2026-11-10T00:00:05Z"));
        first.save(d);

        InMemoryDocumentRepository second = new InMemoryDocumentRepository(file);
        IngestDocument restored = second.findById(id).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(DocStatus.FAILED);
        assertThat(restored.getReason()).contains("服务重启");
    }

    @Test
    void 快照损坏时启动失败而不是静默清空(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("documents.json");
        Files.writeString(file, "{not-json");
        assertThatThrownBy(() -> new InMemoryDocumentRepository(file))
                .isInstanceOf(IllegalStateException.class);
    }
}
