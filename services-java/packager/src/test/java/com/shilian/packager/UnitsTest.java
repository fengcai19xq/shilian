package com.shilian.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.packager.core.Naming;
import com.shilian.packager.model.Entry;
import com.shilian.packager.model.FileRef;
import com.shilian.packager.storage.LocalStorage;
import com.shilian.packager.storage.ObjectStorage;
import com.shilian.packager.storage.StorageError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 命名规则与存储实现的单元测试（移植自 test_units.py）。 */
class UnitsTest {

    @ParameterizedTest
    @CsvSource({"1,01", "3.1,03.1", "12,12", "A.2,A.2", "10.2.1,10.2.1"})
    void padNo(String no, String expected) {
        assertThat(Naming.padNo(no)).isEqualTo(expected);
    }

    @Test
    void sanitizeStripsIllegalChars() {
        assertThat(Naming.sanitize("审计/报告:2023*\"?")).isEqualTo("审计_报告_2023_");
        assertThat(Naming.sanitize("  ")).isEqualTo("_");
    }

    @Test
    void dirAndFileName() {
        Entry entry = Entry.of("1", "营业执照", "2024", List.of());
        FileRef ref = FileRef.of("oss://docs/x.PDF");
        assertThat(Naming.dirName(entry)).isEqualTo("01_营业执照");
        assertThat(Naming.fileName(entry, ref)).isEqualTo("01_营业执照_2024.pdf");
        assertThat(Naming.fileName(entry, new FileRef("a", null, null, "2025"), 1, 2))
                .isEqualTo("01_营业执照_2025_2.pdf");
    }

    @Test
    void entryOrderedFilesStable() {
        Entry entry = Entry.of("1", "x", null, List.of(
                new FileRef("b", null, 2, null), new FileRef("a1", null, 1, null), new FileRef("a2", null, 1, null)));
        assertThat(entry.orderedFiles()).extracting(FileRef::uri).containsExactly("a1", "a2", "b");
    }

    @Test
    void localStorageRoundtrip(@TempDir Path tmp) throws IOException {
        LocalStorage st = new LocalStorage(tmp);
        Files.createDirectories(tmp.resolve("d"));
        Files.write(tmp.resolve("d").resolve("f.pdf"), "x".getBytes());
        assertThat(st.read("oss://d/f.pdf")).isEqualTo("x".getBytes());
        assertThat(st.read("d/f.pdf")).isEqualTo("x".getBytes());
        String uri = st.write("p/out.bin", "y".getBytes());
        assertThat(Files.readAllBytes(Path.of(uri))).isEqualTo("y".getBytes());
        assertThat(st.signedUrl(uri, 60)).contains("expires_in=60");
    }

    @Test
    void localStorageRejectsTraversal(@TempDir Path tmp) {
        LocalStorage st = new LocalStorage(tmp.resolve("root"));
        assertThatThrownBy(() -> st.read("oss://../secret.pdf"))
                .isInstanceOf(StorageError.class).hasMessageContaining("越界");
    }

    @Test
    void objectStorageIsStub() {
        ObjectStorage st = new ObjectStorage("b");
        List<ThrowingCallable> calls = List.of(
                () -> st.read("u"), () -> st.write("n", new byte[0]), () -> st.signedUrl("u"));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
