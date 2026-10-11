package com.shilian.ingest.service;

/** 上传请求（已从 multipart 中取出），校验在 {@link IngestService#submit} 内完成。 */
public record UploadCommand(
        String actor,
        String fileName,
        byte[] content,
        String dept,
        String sensitivity,
        String project,
        String period,
        String scope,
        String title) {}
