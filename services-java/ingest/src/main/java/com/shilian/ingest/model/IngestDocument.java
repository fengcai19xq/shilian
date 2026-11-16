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
    /** 租户标识（WK10 多租户准备）；单租户部署固定 default。 */
    private final String tenant;
    /** 盖章份数（可选，缺省 null 不猜；WK18 登记富化）。 */
    private final Integer copies;
    /** 是否盖章（可选，缺省 null 不猜；WK18 登记富化）。 */
    private final Boolean stamped;
    /** 资料类型（可选，缺省 null 不猜；WK18 登记富化，方案甲人工传 + 方案乙词典兜底）。 */
    private final String stdType;
    /** 原始文件 URI（可选；未配置原始文件存储时为 null）。 */
    private final String storageUri;

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
        this(docId, title, fileName, fileType, sizeBytes, dept, sensitivity, project, period, scope,
                uploadedBy, createdAt, "default", null, null, null, null);
    }

    public IngestDocument(String docId, String title, String fileName, String fileType, long sizeBytes,
            String dept, Sensitivity sensitivity, String project, String period, String scope,
            String uploadedBy, Instant createdAt, String tenant) {
        this(docId, title, fileName, fileType, sizeBytes, dept, sensitivity, project, period, scope,
                uploadedBy, createdAt, tenant, null, null, null, null);
    }

    public IngestDocument(String docId, String title, String fileName, String fileType, long sizeBytes,
            String dept, Sensitivity sensitivity, String project, String period, String scope,
            String uploadedBy, Instant createdAt, String tenant,
            Integer copies, Boolean stamped, String stdType) {
        this(docId, title, fileName, fileType, sizeBytes, dept, sensitivity, project, period, scope,
                uploadedBy, createdAt, tenant, copies, stamped, stdType, null);
    }

    public IngestDocument(String docId, String title, String fileName, String fileType, long sizeBytes,
            String dept, Sensitivity sensitivity, String project, String period, String scope,
            String uploadedBy, Instant createdAt, String tenant,
            Integer copies, Boolean stamped, String stdType, String storageUri) {
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
        this.tenant = tenant == null || tenant.isBlank() ? "default" : tenant;
        this.copies = copies;
        this.stamped = stamped;
        this.stdType = stdType;
        this.storageUri = storageUri;
        this.updatedAt = createdAt;
    }

    public synchronized IngestDocument copy() {
        IngestDocument c = new IngestDocument(docId, title, fileName, fileType, sizeBytes, dept, sensitivity,
                project, period, scope, uploadedBy, createdAt, tenant, copies, stamped, stdType, storageUri);
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
    public String getTenant() { return tenant; }
    public Integer getCopies() { return copies; }
    public Boolean getStamped() { return stamped; }
    public String getStdType() { return stdType; }
    public String getStorageUri() { return storageUri; }
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
