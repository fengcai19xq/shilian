package com.shilian.ingest.parse;

/** 解析失败；message 是给用户看的可读原因，会写入文档的 reason 字段。 */
public class DocumentParseException extends Exception {

    public DocumentParseException(String reason) {
        super(reason);
    }

    public DocumentParseException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
