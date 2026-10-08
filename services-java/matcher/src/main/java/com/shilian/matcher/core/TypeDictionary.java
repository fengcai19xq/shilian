package com.shilian.matcher.core;

import com.shilian.matcher.model.TypeStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 资料类型词典：把清单里的自然语言资料名称归一到 std_type。
 *
 * <p>词典未命中时返回 unknown，<b>不做猜测</b>（猜错会把单体报表当合并报表发出去）。
 */
public final class TypeDictionary {

    public static final String DEFAULT_RESOURCE = "/data/types.yaml";

    private static final Pattern NOISE = Pattern.compile(
            "[\\s\u3000()（）【】\\[\\]〈〉<>《》:：,，.。、/\\\\\\-_*+]", Pattern.UNICODE_CHARACTER_CLASS);

    public record TypeHit(String stdType, String stdName, String status) {
    }

    private record Alias(String key, String stdType) {
    }

    private final Map<String, String> stdNames = new LinkedHashMap<>();
    /** 别名按长度倒序匹配，避免「章程」吃掉「公司章程」。 */
    private final List<Alias> aliasIndex = new ArrayList<>();

    public TypeDictionary(Map<String, ?> types) {
        if (types != null) {
            types.forEach((stdType, rawSpec) -> {
                Map<?, ?> spec = rawSpec instanceof Map<?, ?> m ? m : Map.of();
                Object nameValue = spec.get("std_name");
                String stdName = nameValue == null ? stdType : String.valueOf(nameValue);
                stdNames.put(stdType, stdName);
                Set<String> aliases = new LinkedHashSet<>();
                if (spec.get("aliases") instanceof List<?> list) {
                    list.forEach(a -> aliases.add(String.valueOf(a)));
                }
                aliases.add(stdName);
                for (String alias : aliases) {
                    String key = normalize(alias);
                    if (!key.isEmpty()) {
                        aliasIndex.add(new Alias(key, stdType));
                    }
                }
            });
        }
        aliasIndex.sort(Comparator.comparingInt((Alias a) -> a.key().length()).reversed());
    }

    /** 去噪并统一大小写，便于别名匹配。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return NOISE.matcher(text).replaceAll("").toUpperCase(Locale.ROOT);
    }

    public static TypeDictionary load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return fromYaml(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读取 classpath 下的默认词典 data/types.yaml。 */
    public static TypeDictionary loadDefault() {
        return DefaultHolder.INSTANCE;
    }

    private static TypeDictionary fromYaml(InputStream in) {
        Map<String, Object> data = Yamls.loadMap(in);
        return new TypeDictionary(Yamls.asStringKeyMap(data.get("types")));
    }

    public String stdName(String stdType) {
        return stdNames.get(stdType);
    }

    /** 词典里的类型数。 */
    public int size() {
        return stdNames.size();
    }

    /** 在资料名称中查找最长命中的别名。 */
    public TypeHit resolve(String text) {
        String key = normalize(text);
        if (!key.isEmpty()) {
            for (Alias alias : aliasIndex) {
                if (key.contains(alias.key())) {
                    return new TypeHit(alias.stdType(), stdNames.get(alias.stdType()), TypeStatus.RESOLVED);
                }
            }
        }
        return new TypeHit(TypeStatus.UNKNOWN_TYPE, null, TypeStatus.UNKNOWN);
    }

    private static final class DefaultHolder {
        private static final TypeDictionary INSTANCE;

        static {
            try (InputStream in = Yamls.resource(DEFAULT_RESOURCE)) {
                INSTANCE = fromYaml(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
