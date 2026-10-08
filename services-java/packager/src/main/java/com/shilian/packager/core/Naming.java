package com.shilian.packager.core;

import com.shilian.packager.model.Entry;
import com.shilian.packager.model.FileRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 产物命名规则，集中一处便于与契约对齐。
 *
 * <ul>
 *   <li>zip 目录：{@code 01_营业执照/}（编号首段补零到两位）
 *   <li>zip 文件：{@code 编号_标准名称_期间.pdf}（无期间时省略该段；同一条目多份时追加序号）
 * </ul>
 */
public final class Naming {

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]+");
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");

    private Naming() {}

    /** 清掉文件名里的非法字符与首尾空白，空串回退为下划线。 */
    public static String sanitize(String text) {
        String cleaned = ILLEGAL.matcher(text).replaceAll("_").strip();
        int start = 0;
        int end = cleaned.length();
        while (start < end && cleaned.charAt(start) == '.') {
            start++;
        }
        while (end > start && cleaned.charAt(end - 1) == '.') {
            end--;
        }
        cleaned = SPACES.matcher(cleaned.substring(start, end)).replaceAll(" ");
        return cleaned.isEmpty() ? "_" : cleaned;
    }

    /** 编号首段补零到两位：{@code 1 → 01}，{@code 3.1 → 03.1}，非数字编号原样返回。 */
    public static String padNo(String no) {
        int dot = no.indexOf('.');
        String head = dot >= 0 ? no.substring(0, dot) : no;
        String rest = dot >= 0 ? no.substring(dot) : "";
        if (!head.isEmpty() && head.chars().allMatch(Character::isDigit) && head.length() < 2) {
            head = "0".repeat(2 - head.length()) + head;
        }
        return head + rest;
    }

    /** zip 内的条目目录名。 */
    public static String dirName(Entry entry) {
        return padNo(sanitize(entry.no())) + "_" + sanitize(entry.stdName());
    }

    static String suffix(String uri) {
        String tail = uri.substring(uri.lastIndexOf('/') + 1);
        int dot = tail.lastIndexOf('.');
        return dot >= 0 ? "." + tail.substring(dot + 1).toLowerCase(Locale.ROOT) : ".pdf";
    }

    public static String fileName(Entry entry, FileRef ref) {
        return fileName(entry, ref, 0, 1);
    }

    /** zip 内的文件名：{@code 编号_标准名称_期间.pdf}。 */
    public static String fileName(Entry entry, FileRef ref, int index, int total) {
        List<String> parts = new ArrayList<>();
        parts.add(padNo(sanitize(entry.no())));
        parts.add(sanitize(entry.stdName()));
        String period = notEmpty(ref.period()) ? ref.period() : entry.period();
        if (notEmpty(period)) {
            parts.add(sanitize(period));
        }
        if (total > 1) {
            parts.add(String.valueOf(index + 1));
        }
        return String.join("_", parts) + suffix(ref.uri());
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
