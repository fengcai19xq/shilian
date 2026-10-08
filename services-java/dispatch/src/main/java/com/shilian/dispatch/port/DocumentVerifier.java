package com.shilian.dispatch.port;

import java.util.Optional;

/** 回传校验：确认 doc_id 已在文档库（RAGFlow 入库）中存在，并取其资料类型。 */
public interface DocumentVerifier {

    Optional<DocumentMeta> lookup(String docId);
}
