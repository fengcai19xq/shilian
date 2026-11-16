package com.shilian.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDocumentStorageTest {

    @Test
    void 保存后可读且URI可用于packager(@TempDir Path root) {
        LocalDocumentStorage storage = new LocalDocumentStorage(root);
        byte[] content = "审计报告内容".getBytes(StandardCharsets.UTF_8);
        String uri = storage.save("d_000001", "../审计报告.pdf", content);

        assertThat(uri).isEqualTo("local://docs/d_000001/审计报告.pdf");
        assertThat(Files.exists(root.resolve("docs/d_000001/审计报告.pdf"))).isTrue();
        assertThat(storage.read(uri)).isEqualTo(content);
    }

    @Test
    void 拒绝越界URI(@TempDir Path root) {
        LocalDocumentStorage storage = new LocalDocumentStorage(root);
        assertThatThrownBy(() -> storage.read("local://../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
