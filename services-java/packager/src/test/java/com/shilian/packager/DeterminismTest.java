package com.shilian.packager;

import static org.assertj.core.api.Assertions.assertThat;

import com.shilian.packager.core.PackagingExecutor;
import com.shilian.packager.model.OutputKind;
import com.shilian.packager.storage.LocalStorage;
import java.nio.file.Path;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Java 版新增：同一 manifest 在不同默认时区下产物字节仍一致。 */
class DeterminismTest {

    @Test
    void identicalAcrossTimezones(@TempDir Path tmp) throws Exception {
        LocalStorage storage = Fixtures.storage(tmp);
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Map<OutputKind, byte[]> utc = PackagingExecutor.buildArtifacts(Fixtures.manifest(), storage);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Map<OutputKind, byte[]> cst = PackagingExecutor.buildArtifacts(Fixtures.manifest(), storage);
            for (OutputKind kind : OutputKind.values()) {
                assertThat(cst.get(kind)).as(kind.value()).isEqualTo(utc.get(kind));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
