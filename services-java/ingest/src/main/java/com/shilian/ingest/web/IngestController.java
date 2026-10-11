package com.shilian.ingest.web;

import com.shilian.ingest.service.IngestService;
import com.shilian.ingest.service.UploadCommand;
import com.shilian.ingest.web.Dtos.DocumentList;
import com.shilian.ingest.web.Dtos.DocumentView;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ingest/documents")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentView> upload(
            @RequestHeader(value = "X-User-Id", required = false) String actor,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String dept,
            @RequestParam(required = false) String sensitivity,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String title) throws IOException {
        byte[] content = file == null ? null : file.getBytes();
        String fileName = file == null ? null : file.getOriginalFilename();
        DocumentView view = DocumentView.of(service.submit(
                new UploadCommand(actor, fileName, content, dept, sensitivity, project, period, scope, title)));
        return ResponseEntity.accepted().location(URI.create("/ingest/documents/" + view.docId())).body(view);
    }

    @GetMapping("/{docId}")
    public DocumentView get(@PathVariable String docId) {
        return DocumentView.of(service.get(docId));
    }

    @GetMapping
    public DocumentList list(@RequestParam(required = false) String dept,
            @RequestParam(required = false) String status) {
        List<DocumentView> docs = service.list(dept, status).stream().map(DocumentView::of).toList();
        return new DocumentList(docs, docs.size());
    }
}
