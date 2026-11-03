package com.shilian.ingest.ragflow;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * KB 名与项目标签编码（WK10），与 retrieval-proxy 侧 kb_encoding.py 逐字对齐：
 * 两侧生成的字符串必须完全一致，否则检索侧解析不到本模块创建的 KB / tag。
 * 规则来源：contracts/weknora-integration.md §3、spike/SPIKE-REPORT.md A4。
 *
 *  - KB 名：sl-{slug(tenant)}-{L1..L4}-{slug(dept)}，密级与部门复合进 KB 名
 *  - 项目标签：project::{encoded}；未标注项目 → project::__none__（哨兵）
 *  - encodeProject：ASCII 安全值原样保留；否则 "p" + 每码点 4 位 hex（可逆，供检索侧反解）
 */
public final class KbEncoding {

    public static final String PROJECT_NONE = "__none__";
    public static final String TAG_PREFIX_PROJECT = "project::";

    private static final Set<String> SENSITIVITIES = Set.of("L1", "L2", "L3", "L4");

    /** slug 安全字符集：小写字母数字、下划线、连字符；出现其他字符即整串码点 hex 化。 */
    private static final Pattern SLUG_UNSAFE = Pattern.compile("[^a-z0-9_\\-]");

    private KbEncoding() {}

    /** 标识片段：中文/大写等不安全输入按 Unicode 码点转 hex（无碰撞，与 Python 一致）。 */
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
        if (sensitivity == null || !SENSITIVITIES.contains(sensitivity)) {
            throw new IllegalArgumentException("未知密级: " + sensitivity);
        }
        return "sl-" + slug(tenant) + "-" + sensitivity + "-" + slug(dept);
    }

    /** 项目 → 标签名；null / 空白 → 哨兵标签（对齐「未标注项目所有人可见」）。 */
    public static String projectTag(String project) {
        String value = project == null ? "" : project.strip();
        return TAG_PREFIX_PROJECT + (value.isEmpty() ? PROJECT_NONE : encodeProject(value));
    }

    /** 与 Python encode_project 逐字一致：恒为 "p" + 每码点 4 位 hex，不做明文放行。 */
    static String encodeProject(String value) {
        StringBuilder sb = new StringBuilder("p");
        value.codePoints().forEach(c -> sb.append(String.format("%04x", c)));
        return sb.toString();
    }
}
