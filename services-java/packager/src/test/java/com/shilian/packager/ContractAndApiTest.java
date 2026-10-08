package com.shilian.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.shilian.packager.core.PackagingExecutor;
import com.shilian.packager.model.EntryStatus;
import com.shilian.packager.model.Manifest;
import com.shilian.packager.model.Manifests;
import com.shilian.packager.model.OutputKind;
import com.shilian.packager.model.PackageResult;
import com.shilian.packager.storage.InMemoryStorage;
import com.shilian.packager.storage.Storage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Java 版新增：契约 JSON（snake_case）、内存 fake 存储、HTTP 入口。 */
@SpringBootTest(properties = "packager.storage.type=memory")
@AutoConfigureMockMvc
class ContractAndApiTest {

    /** contracts/packaging.md 中的 manifest 示例原文。 */
    static final String CONTRACT_EXAMPLE = """
            {
              "package_id": "pkg_0007_v1",
              "title": "中信银行授信资料包",
              "watermark": "仅供中信银行授信审查使用 · 2026-03-12",
              "outputs": ["zip", "merged_pdf", "checklist_xlsx"],
              "entries": [
                {
                  "no": "3.1",
                  "std_name": "最近三年审计报告",
                  "files": [
                    { "uri": "oss://docs/d_2031.pdf", "pages": null, "order": 1 }
                  ],
                  "status": "matched",
                  "note": ""
                },
                {
                  "no": "6.3",
                  "std_name": "最新版公司章程",
                  "files": [],
                  "status": "missing",
                  "note": "库内仅 2022 版，需最新备案版本"
                }
              ]
            }
            """;

    @Autowired MockMvc mvc;
    @Autowired Storage storage;

    @Test
    void contractExampleParses() {
        Manifest m = Manifests.parse(CONTRACT_EXAMPLE);
        assertThat(m.packageId()).isEqualTo("pkg_0007_v1");
        assertThat(m.outputs()).containsExactly(OutputKind.values());
        assertThat(m.entries().get(0).stdName()).isEqualTo("最近三年审计报告");
        assertThat(m.entries().get(0).files().get(0).pages()).isNull();
        assertThat(m.entries().get(1).status()).isEqualTo(EntryStatus.MISSING);
    }

    @Test
    void inMemoryStorageAndSnakeCaseResult() throws Exception {
        InMemoryStorage mem = new InMemoryStorage().put("oss://docs/d_2031.pdf", Fixtures.makePdf(2, "AUDIT"));
        PackageResult result = PackagingExecutor.execute(Manifests.parse(CONTRACT_EXAMPLE), mem);
        assertThat(result.artifacts()).hasSize(3);
        assertThat(mem.output(result.artifact(OutputKind.ZIP).orElseThrow().uri())).isNotEmpty();
        JsonNode json = Manifests.mapper().valueToTree(result);
        assertThat(json.fieldNames()).toIterable()
                .containsExactly("package_id", "artifacts", "missing_entries", "requires_manual_review");
        assertThat(json.get("artifacts").get(1).get("kind").asText()).isEqualTo("merged_pdf");
        assertThat(json.get("missing_entries").get(0).asText()).isEqualTo("6.3");
    }

    @Test
    void httpExecute() throws Exception {
        ((InMemoryStorage) storage).put("oss://docs/d_2031.pdf", Fixtures.makePdf(2, "AUDIT"));
        mvc.perform(post("/v1/packages").contentType(MediaType.APPLICATION_JSON).content(CONTRACT_EXAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.package_id").value("pkg_0007_v1"))
                .andExpect(jsonPath("$.requires_manual_review").value(true))
                .andExpect(jsonPath("$.missing_entries[0]").value("6.3"))
                .andExpect(jsonPath("$.artifacts[2].filename").value("pkg_0007_v1/pkg_0007_v1_核对表.xlsx"));
    }

    @Test
    void httpRejectsInvalidManifest() throws Exception {
        mvc.perform(post("/v1/packages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"package_id\":\"x\",\"title\":\"t\",\"outputs\":[\"docx\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/packages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"package_id\":\"x\",\"title\":\"t\",\"entries\":[{\"no\":\"1\",\"std_name\":\"a\","
                                + "\"files\":[{\"uri\":\"a\",\"pages\":[0]}]}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("packaging_error"));
    }
}
