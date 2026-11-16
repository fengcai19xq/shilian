package com.shilian.ingest.store;

/** 未配置原始文件存储时的空实现：只登记元数据，不保存文件。 */
public class NoopDocumentStorage implements DocumentStorage {

    @Override
    public String save(String docId, String fileName, byte[] content) {
        return null;
    }

    @Override
    public byte[] read(String uri) {
        throw new UnsupportedOperationException("未配置原始文件存储");
    }
}
