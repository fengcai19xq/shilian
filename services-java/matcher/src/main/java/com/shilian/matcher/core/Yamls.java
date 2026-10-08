package com.shilian.matcher.core;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** YAML 读取工具（SafeConstructor，不实例化任意类型）。 */
final class Yamls {

    private Yamls() {
    }

    static InputStream resource(String name) {
        InputStream in = Yamls.class.getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("缺少资源文件：" + name);
        }
        return in;
    }

    static Map<String, Object> loadMap(InputStream in) {
        Object data = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        return asStringKeyMap(data);
    }

    static Map<String, Object> asStringKeyMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }
}
