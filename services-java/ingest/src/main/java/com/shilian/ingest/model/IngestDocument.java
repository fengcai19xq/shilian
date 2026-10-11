package com.shilian.ingest.model;

import java.time.Instant;

/** 入库文档登记记录（不含文件内容）。由 Repository 持有，修改后需 save 回去。 */
public class IngestDocument {

    private final String docId;
    private final String title;
    private final String fileName;
    private final String fileType;
    private final long sizeBytes;
    private final String dept;
    private final Sensitivity sensitivity;
    private final String project;
    private final String period;
    private final String scope;
    private final String uploadedBy;
    private final Instant createdAt;

    private DocStatus status = DocStatus.UPLOADED;
    private String reason;
    private int pageCount;
    private int chunkCount;
    private String datasetId;
    private String ragflowDocId;
    private Instant updatedAt;

    public IngestDocument(String docId, String title, String fileName, String fileType, long sizeBytes,
            String dept, Sensitivity sensitivity, String project, String period, String scope,
            String uploadedBy, Instant createdAt) {
        this.docId = docId;
        this.title = title;
        this.fileName = fileName;
        this.fileType = fileType;
        this.sizeBytes = sizeBytes;
        this.dept = dept;
        this.sensitivity = sensitivity;
        this.project = project;
        this.period = period;
        this.scope = scope;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public synchronized IngestDocument copy() {
        IngestDocument c = new IngestDocument(docId, title, fileName, fileType, sizeBytes, dept, sensitivity,
                project, period, scope, uploadedBy, createdAt);
        c.status = status;
        c.reason = reason;
        c.pageCount = pageCount;
        c.chunkCount = chunkCount;
        c.datasetId = datasetId;
        c.ragflowDocId = ragflowDocId;
        c.updatedAt = updatedAt;
        return c;
    }

    public String getDocId() { return docId; }
    public String getTitle() { return title; }
    public String getFileName() { return fileName; }
    public String getFileType() { return fileType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getDept() { return dept; }
    public Sensitivity getSensitivity() { return sensitivity; }
    public String getProject() { return project; }
    public String getPeriod() { return period; }
    public String getScope() { return scope; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public synchronized DocStatus getStatus() { return status; }
    public synchronized String getReason() { return reason; }
    public synchronized int getPageCount() { return pageCount; }
    public synchronized int getChunkCount() { return chunkCount; }
    public synchronized String getDatasetId() { return datasetId; }
    public synchronized String getRagflowDocId() { return ragflowDocId; }
    public synchronized Instant getUpdatedAt() { return updatedAt; }

    public synchronized void setStatus(DocStatus status, String reason, Instant at) {
        this.status = status;
        this.reason = reason;
        this.updatedAt = at;
    }

    public synchronized void setParseResult(int pageCount, int chunkCount) {
        this.pageCount = pageCount;
        this.chunkCount = chunkCount;
    }

    public synchronized void setRagflowRefs(String datasetId, String ragflowDocId) {
        this.datasetId = datasetId;
        this.ragflowDocId = ragflowDocId;
    }
}
