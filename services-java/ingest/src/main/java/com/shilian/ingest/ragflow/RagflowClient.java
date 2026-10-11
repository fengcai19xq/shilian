package com.shilian.ingest.ragflow;

import java.util.List;
import java.util.Map;

/**
 * RAGFlow 知识库写入接口。当前只有内存实现 {@link FakeRagflowClient}。
 * TODO(T06-real)：实现基于 RAGFlow HTTP API 的客户端（/api/v1/datasets、/documents、/chunks），
 * base-url 与 api-key 从 ingest.ragflow.* 配置（环境变量）读取，失败统一抛 {@link RagflowException}。
 */
public interface RagflowClient {

    /** 创建数据集，返回 dataset_id；同名数据集已存在时返回已有 id（幂等）。 */
    String createDataset(String name);

    /** 上传原始文件并写入文档级元数据（dept / sensitivity / project / period / scope），返回 RAGFlow 文档 id。 */
    String uploadDocument(String datasetId, String fileName, byte[] content, Map<String, String> metadata);

    /** 按 chunkId 写入或覆盖切块。 */
    void upsertChunks(String datasetId, String documentId, List<RagflowChunk> chunks);

    /** 删除文档及其全部切块；文档不存在时静默返回。 */
    void deleteDocument(String datasetId, String documentId);
}
