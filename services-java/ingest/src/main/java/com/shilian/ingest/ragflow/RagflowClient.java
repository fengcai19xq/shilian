package com.shilian.ingest.ragflow;

import java.util.List;
import java.util.Map;

/**
 * 知识库写入通道接口（历史名 RagflowClient，WK10 起语义为「推送通道」）。
 * 默认方法面向 RAGFlow（本地解析、手动推块）；WeKnora 通道覆写为服务端解析（WK10）。
 */
public interface RagflowClient {

    /** 服务端解析统计（serverSideParsing 通道在 waitForIndexed 成功后返回）。 */
    record ParseStats(int pageCount, int chunkCount) {}

    /** 创建数据集，返回 dataset_id；同名数据集已存在时返回已有 id（幂等）。 */
    String createDataset(String name);

    /** 上传原始文件并写入文档级元数据（dept / sensitivity / project / period / scope），返回文档 id。 */
    String uploadDocument(String datasetId, String fileName, byte[] content, Map<String, String> metadata);

    /** 按 chunkId 写入或覆盖切块。 */
    void upsertChunks(String datasetId, String documentId, List<RagflowChunk> chunks);

    /** 删除文档及其全部切块；文档不存在时静默返回。 */
    void deleteDocument(String datasetId, String documentId);

    /** true = 解析与切块由服务端完成（WeKnora docreader），本地不再解析、不推块。 */
    default boolean serverSideParsing() {
        return false;
    }

    /**
     * serverSideParsing 通道：轮询服务端直到文档解析+建索引完成，返回页数/块数。
     * 失败或超时抛 {@link RagflowException}（解析失败用 {@link ServerSideParseException} 携带用户可见 reason）。
     */
    default ParseStats waitForIndexed(String datasetId, String documentId) {
        throw new UnsupportedOperationException("该通道不做服务端解析");
    }
}
