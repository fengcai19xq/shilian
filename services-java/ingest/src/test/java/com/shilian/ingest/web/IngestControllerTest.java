package com.shilian.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.ingest.audit.AuditEvent;
import com.shilian.ingest.audit.InMemoryAuditSink;
import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.Sensitivity;
import com.shilian.ingest.ragflow.FakeRagflowClient;
import com.shilian.ingest.ragflow.RagflowChunk;
import com.shilian.ingest.store.InMemoryDocumentRepository;
import com.shilian.ingest.support.Samples;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest(properties = {"ingest.async=false", "ingest.chunk.size=200", "ingest.chunk.overlap=20"})
@AutoConfigureMockMvc
class IngestControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FakeRagflowClient ragflow;
    @Autowired InMemoryDocumentRepository repo;
    @Autowired InMemoryAuditSink audit;

    @BeforeEach
    void reset() {
        ragflow.clear();
        repo.clear();
        audit.clear();
    }

    private MockMultipartHttpServletRequestBuilder upload(String fileName, byte[] content) {
        MockMultipartHttpServletRequestBuilder req = multipart("/ingest/documents");
        if (fileName != null) {
            req.file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        }
        return req;
    }

    private ResultActions post(String fileName, String sample, String dept, String sensitivity) throws Exception {
        MockMultipartHttpServletRequestBuilder req = upload(fileName, Samples.read(sample));
        if (dept != null) {
            req.param("dept", dept);
        }
        if (sensitivity != null) {
            req.param("sensitivity", sensitivity);
        }
        return mvc.perform(req.header("X-User-Id", "u_10001"));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest
    @CsvSource({
        "简报.docx, sample.docx, docx, 2, 研发费用1234万元, 2",
        "审计摘要.pdf, sample.pdf, pdf, 2, 5.6 percent, 2",
        "报表.xlsx, sample.xlsx, xlsx, 2, 货币资金, 2",
        "汇报.pptx, sample.pptx, pptx, 3, 已完成尽职调查, 2"
    })
    void uploadsAndIndexesEachSupportedFormat(String fileName, String sample, String type, int pages,
            String expectedText, int expectedPage) throws Exception {
        ResultActions r = mvc.perform(upload(fileName, Samples.read(sample))
                        .param("dept", "财务共享").param("sensitivity", "L3")
                        .param("project", "PRJ-2026-DEMO").param("period", "2023").param("scope", "合并")
                        .header("X-User-Id", "u_10001"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/ingest/documents/d_")))
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.file_type").value(type))
                .andExpect(jsonPath("$.title").value(fileName))
                .andExpect(jsonPath("$.page_count").value(pages))
                .andExpect(jsonPath("$.uploaded_by").value("u_10001"));
        JsonNode doc = body(r);
        assertThat(doc.get("chunk_count").asInt()).isPositive();

        FakeRagflowClient.StoredDocument stored = ragflow.document(doc.get("ragflow_doc_id").asText());
        assertThat(stored.fileName()).isEqualTo(fileName);
        assertThat(stored.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "dept", "财务共享", "sensitivity", "L3", "project", "PRJ-2026-DEMO",
                "period", "2023", "scope", "合并"));
        List<RagflowChunk> chunks = ragflow.chunks(doc.get("ragflow_doc_id").asText());
        assertThat(chunks).hasSize(doc.get("chunk_count").asInt());
        assertThat(chunks.get(0).chunkId()).isEqualTo(doc.get("doc_id").asText() + "_c0000");
        assertThat(chunks).allSatisfy(c -> assertThat(c.metadata()).isEqualTo(stored.metadata()));
        assertThat(chunks).anySatisfy(c -> {
            assertThat(c.content()).contains(expectedText);
            assertThat(c.page()).isEqualTo(expectedPage);
        });
    }

    @Test
    void optionalMetadataIsOmittedAndTitleCanBeOverridden() throws Exception {
        ResultActions r = mvc.perform(upload("a.docx", Samples.read("sample.docx"))
                        .param("dept", "融资部").param("sensitivity", "l1").param("title", "经营简报")
                        .header("X-User-Id", "u_10002"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.title").value("经营简报"))
                .andExpect(jsonPath("$.sensitivity").value("L1"))
                .andExpect(jsonPath("$.project").isEmpty());
        assertThat(ragflow.document(body(r).get("ragflow_doc_id").asText()).metadata())
                .containsOnlyKeys("dept", "sensitivity");
    }

    @Test
    void l4IsRegisteredButNeverPushed() throws Exception {
        ResultActions r = post("绝密.pdf", "sample.pdf", "财务共享", "L4")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("held_l4"))
                .andExpect(jsonPath("$.reason").value(containsString("L4 不出 VPC")))
                .andExpect(jsonPath("$.ragflow_doc_id").isEmpty())
                .andExpect(jsonPath("$.chunk_count").value(0));
        assertThat(ragflow.calls()).isEmpty();
        assertThat(ragflow.documents()).isEmpty();

        String id = body(r).get("doc_id").asText();
        mvc.perform(get("/ingest/documents/" + id))
                .andExpect(jsonPath("$.status").value("held_l4"));
        assertThat(audit.list(id)).extracting(AuditEvent::action).containsExactly("uploaded", "held_l4");
        assertThat(audit.list(id).get(1).reason()).contains("L4 不出 VPC");
        assertThat(audit.list(id)).allMatch(e -> e.sensitivity() == Sensitivity.L4);
    }

    @Test
    void missingDeptReturns400() throws Exception {
        post("a.docx", "sample.docx", null, "L2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("dept")));
        post("a.docx", "sample.docx", "  ", "L2").andExpect(status().isBadRequest());
        assertNothingRecorded();
    }

    @Test
    void missingOrInvalidSensitivityReturns400() throws Exception {
        post("a.docx", "sample.docx", "财务共享", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("sensitivity")));
        post("a.docx", "sample.docx", "财务共享", "L5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("L1~L4")));
        assertNothingRecorded();
    }

    @Test
    void missingFileOrActorReturns400() throws Exception {
        mvc.perform(upload(null, null).param("dept", "财务共享").param("sensitivity", "L2")
                        .header("X-User-Id", "u_10001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("file")));
        mvc.perform(upload("empty.docx", new byte[0]).param("dept", "财务共享").param("sensitivity", "L2")
                        .header("X-User-Id", "u_10001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("为空")));
        mvc.perform(upload("a.docx", Samples.read("sample.docx")).param("dept", "财务共享")
                        .param("sensitivity", "L2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("X-User-Id")));
        assertNothingRecorded();
    }

    @ParameterizedTest
    @CsvSource({
        "加密.pdf, encrypted.pdf, PDF 已加密",
        "加密.docx, encrypted.docx, 文档已加密",
        "损坏.docx, corrupt.docx, 文件损坏",
        "旧版.docx, legacy.docx, 旧版 Office",
        "扫描件.pdf, blank.pdf, OCR",
        "notes.txt, notes.txt, 不支持的文件类型: .txt"
    })
    void failuresCarryReadableReason(String fileName, String sample, String reason) throws Exception {
        ResultActions r = post(fileName, sample, "财务共享", "L2")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.reason").value(containsString(reason)));
        String id = body(r).get("doc_id").asText();
        mvc.perform(get("/ingest/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.reason").value(containsString(reason)));
        assertThat(ragflow.documents()).isEmpty();
        assertThat(audit.list(id)).extracting(AuditEvent::action).containsExactly("uploaded", "parsing", "failed");
        assertThat(audit.list(id).get(2).reason()).contains(reason);
    }

    @Test
    void ragflowFailureRollsBackAndMarksFailed() throws Exception {
        ragflow.failNextUpsert("simulated 503");
        ResultActions r = post("a.docx", "sample.docx", "财务共享", "L2")
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.reason").value(containsString("推送 RAGFlow 失败")))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.not(containsString("503"))));
        assertThat(ragflow.documents()).isEmpty();
        assertThat(ragflow.calls()).anyMatch(c -> c.startsWith("deleteDocument:"));

        ragflow.failNextUpload("simulated timeout");
        post("a.docx", "sample.docx", "财务共享", "L2")
                .andExpect(jsonPath("$.status").value("failed"));
        assertThat(body(r).get("ragflow_doc_id").isNull()).isTrue();
    }

    @Test
    void everyTransitionIsAudited() throws Exception {
        String id = body(post("a.xlsx", "sample.xlsx", "财务共享", "L3")).get("doc_id").asText();
        List<AuditEvent> events = audit.list(id);
        assertThat(events).extracting(AuditEvent::action).containsExactly("uploaded", "parsing", "indexed");
        assertThat(events).extracting(AuditEvent::fromStatus)
                .containsExactly(null, DocStatus.UPLOADED, DocStatus.PARSING);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.actor()).isEqualTo("u_10001");
            assertThat(e.docId()).isEqualTo(id);
            assertThat(e.sensitivity()).isEqualTo(Sensitivity.L3);
            assertThat(e.ts()).isNotNull();
        });
    }

    @Test
    void getByIdAnd404() throws Exception {
        String id = body(post("a.pptx", "sample.pptx", "融资部", "L2")).get("doc_id").asText();
        mvc.perform(get("/ingest/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doc_id").value(id))
                .andExpect(jsonPath("$.dept").value("融资部"))
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.created_at").isString());
        mvc.perform(get("/ingest/documents/d_999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void listFiltersByDeptAndStatus() throws Exception {
        post("a.docx", "sample.docx", "财务共享", "L2");
        post("b.pdf", "encrypted.pdf", "财务共享", "L2");
        post("c.xlsx", "sample.xlsx", "融资部", "L2");
        post("d.pdf", "sample.pdf", "融资部", "L4");

        mvc.perform(get("/ingest/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.documents", hasSize(4)));
        mvc.perform(get("/ingest/documents").param("dept", "财务共享"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/ingest/documents").param("dept", "财务共享").param("status", "failed"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.documents[0].file_name").value("b.pdf"));
        mvc.perform(get("/ingest/documents").param("status", "held_l4"))
                .andExpect(jsonPath("$.documents[0].file_name").value("d.pdf"));
        mvc.perform(get("/ingest/documents").param("dept", "").param("status", "indexed"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/ingest/documents").param("status", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("status")));
    }

    private void assertNothingRecorded() {
        assertThat(repo.find(null, null)).isEmpty();
        assertThat(audit.list(null)).isEmpty();
        assertThat(ragflow.calls()).isEmpty();
    }
}
