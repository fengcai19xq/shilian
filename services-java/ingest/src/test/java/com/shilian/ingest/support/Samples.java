package com.shilian.ingest.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** 读取 src/test/resources/samples 下的样例文件。 */
public final class Samples {

    private Samples() {}

    public static byte[] read(String name) {
        try (InputStream in = Samples.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("sample not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
