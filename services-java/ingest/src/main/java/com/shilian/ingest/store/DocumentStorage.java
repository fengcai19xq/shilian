package com.shilian.ingest.store;

/**
 * 原始文件存储。与登记仓库解耦：登记只存元数据，原始文件按 doc_id 落对象存储/本地卷，
 * 供 packager 读取。L4 文档也必须落在 VPC 内（当前实现为本地卷）。
 */
public interface DocumentStorage {

    /**
     * 保存原始文件，返回可写给 packager 的 URI（如 {@code local://docs/d_000001/a.pdf}）。
     */
    String save(String docId, String fileName, byte[] content);

    byte[] read(String uri);
}
