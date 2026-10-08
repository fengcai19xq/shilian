package com.shilian.dispatch.port;

/** 文档库中的文档元数据（回传校验用）。stdType 可空，空则不校验资料类型。 */
public record DocumentMeta(String docId, String stdType) {
}
