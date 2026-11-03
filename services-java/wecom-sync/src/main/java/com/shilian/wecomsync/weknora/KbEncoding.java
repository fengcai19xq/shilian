package com.shilian.wecomsync.weknora;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * KB 名与项目标签编码，规则与 retrieval-proxy 的 kb_encoding.py、
 * ingest 的 KbEncoding 三处必须逐字一致。
 *
 * 任一处偏离都会让「同步侧建的库」与「检索侧解析的库」对不上，
 * 表现为文档入库成功但永远查不到——这类缺陷不会报错，只会静默漏料。
 * 一致性由 KbEncodingTest 的期望串钉死（期望值取自 Python 实现实际输出）。
 * 规则来源：contracts/weknora-integration.md §3。
 */
public final class KbEncoding {

    public static final String PROJECT_NONE = "__none__";
    public static final String TAG_PREFIX_PROJECT = "project::";

    /** 与 ingest 侧同一份密级序。 */
    public static final Set<String> SENSITIVITY_ORDER = Set.of("L1", "L2", "L3", "L4");
    /** 由低到高的顺序；KB 预置按 部门 × 全部密级 叉乘建库时用。 */
    public static final String[] SENSITIVITIES = {"L1", "L2", "L3", "L4"};

    private static final Pattern SLUG_UNSAFE = Pattern.compile("[^a-z0-9_\\-]");

    private KbEncoding() {}

    public static String slug(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("空值不能作为标识片段");
        }
        String lower = value.toLowerCase();
        boolean ascii = value.chars().allMatch(c -> c < 128);
        if (!ascii || SLUG_UNSAFE.matcher(lower).find()) {
            StringBuilder hex = new StringBuilder();
            value.codePoints().forEach(c -> hex.append(Integer.toHexString(c)));
            return hex.toString();
        }
        return lower;
    }

    public static String kbName(String tenant, String sensitivity, String dept) {
        if (sensitivity == null || !SENSITIVITY_ORDER.contains(sensitivity)) {
            throw new IllegalArgumentException("未知密级: " + sensitivity);
        }
        return "sl-" + slug(tenant) + "-" + sensitivity + "-" + slug(dept);
    }

    /** 项目标签名；未标注项目返回哨兵标签。KB 预置遍历 4 个密级，故不做密级展开。 */
    public static String projectTag(String project) {
        String value = project == null ? "" : project.strip();
        return TAG_PREFIX_PROJECT + (value.isEmpty() ? PROJECT_NONE : encodeProject(value));
    }

    /** 恒为 p + 每码点 4 位 hex（可逆，与 Python encode_project 一致）。 */
    static String encodeProject(String value) {
        StringBuilder sb = new StringBuilder("p");
        value.codePoints().forEach(c -> sb.append(String.format("%04x", c)));
        return sb.toString();
    }
}
