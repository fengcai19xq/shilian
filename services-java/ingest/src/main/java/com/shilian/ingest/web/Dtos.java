package com.shilian.ingest.web;

import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.IngestDocument;
import com.shilian.ingest.model.Sensitivity;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() {}

    public record DocumentView(
            String docId,
            String title,
            String fileName,
            String fileType,
            long sizeBytes,
            String dept,
            Sensitivity sensitivity,
            String project,
            String period,
            String scope,
            DocStatus status,
            String reason,
            int pageCount,
            int chunkCount,
            String datasetId,
            String ragflowDocId,
            String uploadedBy,
            Instant createdAt,
            Instant updatedAt) {

        static DocumentView of(IngestDocument d) {
            return new DocumentView(d.getDocId(), d.getTitle(), d.getFileName(), d.getFileType(), d.getSizeBytes(),
                    d.getDept(), d.getSensitivity(), d.getProject(), d.getPeriod(), d.getScope(), d.getStatus(),
                    d.getReason(), d.getPageCount(), d.getChunkCount(), d.getDatasetId(), d.getRagflowDocId(),
                    d.getUploadedBy(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record DocumentList(List<DocumentView> documents, int total) {}

    public record ErrorResponse(String error, String message) {}
}
