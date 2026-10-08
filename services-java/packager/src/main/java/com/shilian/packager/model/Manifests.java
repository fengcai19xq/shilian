package com.shilian.packager.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.shilian.packager.PackagingError;
import java.util.Map;

/** manifest 解析与校验：结构化校验失败一律抛 {@link PackagingError}。 */
public final class Manifests {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Manifests() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static Manifest parse(Map<String, ?> raw) {
        Manifest m;
        try {
            m = MAPPER.convertValue(raw, Manifest.class);
        } catch (IllegalArgumentException e) {
            throw new PackagingError("manifest 不合法：" + rootMessage(e), e);
        }
        return validate(m);
    }

    public static Manifest parse(String json) {
        Manifest m;
        try {
            m = MAPPER.readValue(json, Manifest.class);
        } catch (Exception e) {
            throw new PackagingError("manifest 不合法：" + rootMessage(e), e);
        }
        return validate(m);
    }

    /** 补齐 Jackson 不做的必填与取值校验。 */
    public static Manifest validate(Manifest m) {
        if (m == null) {
            throw new PackagingError("manifest 不合法：为空");
        }
        require(m.packageId() != null, "缺少 package_id");
        require(m.title() != null, "缺少 title");
        for (Entry e : m.entries()) {
            require(e.no() != null, "entry 缺少 no");
            require(e.stdName() != null, "entry " + e.no() + " 缺少 std_name");
            for (FileRef f : e.files()) {
                require(f.uri() != null, "entry " + e.no() + " 的文件缺少 uri");
                if (f.pages() != null) {
                    for (Integer p : f.pages()) {
                        require(p != null && p >= 1, "pages 为 1 起算的页码，不能小于 1");
                    }
                }
            }
        }
        return m;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new PackagingError("manifest 不合法：" + message);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        return nl >= 0 ? msg.substring(0, nl) : msg;
    }
}
