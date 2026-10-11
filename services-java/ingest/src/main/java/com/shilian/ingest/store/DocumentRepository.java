package com.shilian.ingest.store;

import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import java.util.List;
import java.util.Optional;

/** 文档登记存储；默认内存实现，可替换为数据库实现。 */
public interface DocumentRepository {

    /** 分配新的文档 ID（d_ 前缀）。 */
    String nextId();

    void save(IngestDocument doc);

    /** 返回副本，调用方修改后需 save。 */
    Optional<IngestDocument> findById(String docId);

    /** dept / status 为 null 表示不过滤；按创建顺序返回。 */
    List<IngestDocument> find(String dept, DocStatus status);
}
