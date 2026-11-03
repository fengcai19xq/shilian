package com.shilian.ingest.ragflow;

/**
 * 服务端解析失败（加密 / 损坏 / 不支持类型等）。
 * userReason 是给登记记录的固定分类文案——不透传服务端原文（可能含文件名或内容片段）。
 */
public class ServerSideParseException extends RagflowException {

    private final String userReason;

    public ServerSideParseException(String message, String userReason) {
        super(message);
        this.userReason = userReason;
    }

    public String getUserReason() {
        return userReason;
    }
}
