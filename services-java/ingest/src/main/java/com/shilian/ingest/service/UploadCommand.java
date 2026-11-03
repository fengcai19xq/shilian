package com.shilian.ingest.service;

/** 上传请求（已从 multipart 中取出），校验在 {@link IngestService#submit} 内完成。
 *  tenant 为可选组件（WK10 多租户准备）：缺省走单租户默认值，旧调用方用 9 参构造不受影响。 */
public record UploadCommand(
        String actor,
        String fileName,
        byte[] content,
        String dept,
        String sensitivity,
        String project,
        String period,
        String scope,
        String title,
        String tenant) {

    public static final String DEFAULT_TENANT = "default";

    public UploadCommand(String actor, String fileName, byte[] content, String dept, String sensitivity,
            String project, String period, String scope, String title) {
        this(actor, fileName, content, dept, sensitivity, project, period, scope, title, DEFAULT_TENANT);
    }

    public String tenantOrDefault() {
        return tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant.strip();
    }
}
