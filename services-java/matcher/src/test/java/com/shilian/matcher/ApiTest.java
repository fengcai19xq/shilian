package com.shilian.matcher;

import static com.shilian.matcher.support.Fixtures.doc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.matcher.config.MatcherJson;
import com.shilian.matcher.model.AuditEvent;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.Scope;
import com.shilian.matcher.service.MatcherService;
import com.shilian.matcher.support.FakeDecideProvider;
import com.shilian.matcher.support.Fixtures;
import com.shilian.matcher.support.StaticCandidateProvider;
import com.shilian.matcher.web.MatcherController;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 接口层：上传、异步匹配、查进度、人工确认（含审计）、导出 manifest（对应 Python tests/test_api.py）。
 *
 * <p>后台执行器注入同步实现（Runnable::run），等价于 FastAPI TestClient 在响应前跑完 BackgroundTasks。
 */
class ApiTest {

    static final String CHECKLIST_TEXT = "3.1 2023年度审计报告（合并口径，加盖公章）\n"
            + "4.2 2023年度财务报表（合并口径）\n"
            + "6.3 公司章程\n"
            + "7.1 环评批复文件\n";

    static final ObjectMapper JSON = MatcherJson.mapper();

    MatcherService svc;
    MockMvc client;

    @BeforeEach
    void setUp() {
        Map<String, List<CandidateDoc>> byType = Map.of(
                "AUDIT_REPORT", List.of(
                        doc().docId("d_2031").copies(null).build(),
                        // 期间不符：2022 报表匹 2023 项，即便 decide 给满分也必须 rejected
                        doc().docId("d_2022").uri("oss://docs/d_2022.pdf").period("2022").build()),
                "FINANCIAL_STATEMENT", List.of(
                        doc().docId("d_fs_single").stdType("FINANCIAL_STATEMENT").uri("oss://docs/d_fs_single.pdf")
                                .scope(Scope.STANDALONE).build(),
                        doc().docId("d_fs").stdType("FINANCIAL_STATEMENT").uri("oss://docs/d_fs.pdf")
                                .recallScore(0.6).formScore(0.6).build()),
                "ARTICLES_OF_ASSOCIATION", List.of(
                        doc().docId("d_aoa").stdType("ARTICLES_OF_ASSOCIATION").period("2022")
                                .recallScore(0.2).formScore(0.2).build()));
        FakeDecideProvider decide = new FakeDecideProvider(
                Map.of("d_2031", 0.95, "d_2022", 1.0, "d_fs_single", 1.0, "d_fs", 0.6, "d_aoa", 0.3));
        svc = new MatcherService(StaticCandidateProvider.byType(byType), decide, Fixtures.config());
        client = clientFor(svc);
    }

    static MockMvc clientFor(MatcherService service) {
        return MockMvcBuilders.standaloneSetup(new MatcherController(service, Runnable::run))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MatcherJson.mapper()))
                .build();
    }

    static MockHttpServletResponse call(MockMvc mvc, RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JSON.readTree(r.getContentAsString(StandardCharsets.UTF_8));
    }

    static JsonNode upload(MockMvc mvc) throws Exception {
        MockHttpServletResponse r = call(mvc, post("/matcher/checklists")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("text", CHECKLIST_TEXT).param("title", "测试授信资料包")
                .header("X-User-Id", "u_test"));
        assertThat(r.getStatus()).as(r.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        return json(r);
    }

    static JsonNode run(MockMvc mvc, String checklistId) throws Exception {
        MockHttpServletResponse r = call(mvc, post("/matcher/checklists/" + checklistId + "/run"));
        assertThat(r.getStatus()).isEqualTo(202);
        JsonNode body = json(r);
        assertThat(body.get("task_id").asText()).startsWith("tk_");
        r = call(mvc, get("/matcher/tasks/" + body.get("task_id").asText()));
        assertThat(r.getStatus()).isEqualTo(200);
        return json(r);
    }

    static Map<String, JsonNode> byNo(JsonNode array) {
        Map<String, JsonNode> out = new HashMap<>();
        array.forEach(n -> out.put(n.get("no").asText(), n));
        return out;
    }

    static List<String> texts(JsonNode array) {
        return JSON.convertValue(array, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    static byte[] xlsx(String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var ws = wb.createSheet();
            for (int r = 0; r < rows.length; r++) {
                Row row = ws.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void uploadParsesItems() throws Exception {
        JsonNode cl = upload(client);
        List<String> nos = new java.util.ArrayList<>();
        cl.get("items").forEach(i -> nos.add(i.get("no").asText()));
        assertThat(nos).containsExactly("3.1", "4.2", "6.3", "7.1");
        assertThat(cl.get("items").get(0).get("constraints")).isEqualTo(JSON.readTree(
                "{\"period\": [\"2023\"], \"scope\": \"consolidated\", \"copies\": null, \"stamp_required\": true}"));
        assertThat(cl.get("items").get(3).get("type_status").asText()).isEqualTo("unknown");
    }

    @Test
    void uploadExcelFile() throws Exception {
        byte[] content = xlsx(new String[] {"序号", "材料名称", "具体要求"}, new String[] {"1", "营业执照", "加盖公章"});
        MockHttpServletResponse r = call(client, multipart("/matcher/checklists")
                .file(new MockMultipartFile("file", "清单.xlsx", "application/octet-stream", content)));
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(json(r).get("items").get(0).get("std_type").asText()).isEqualTo("BUSINESS_LICENSE");
    }

    @Test
    void uploadRequiresContent() throws Exception {
        MockHttpServletResponse r = call(client, post("/matcher/checklists")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("title", "x"));
        assertThat(r.getStatus()).isEqualTo(400);
    }

    @Test
    void uploadBadExcelReturns422() throws Exception {
        MockHttpServletResponse r = call(client, multipart("/matcher/checklists")
                .file(new MockMultipartFile("file", "bad.xlsx", "x", "not-an-xlsx".getBytes(StandardCharsets.UTF_8))));
        assertThat(r.getStatus()).isEqualTo(422);
    }

    @Test
    void runAndTaskProgress() throws Exception {
        JsonNode cl = upload(client);
        JsonNode task = run(client, cl.get("checklist_id").asText());
        assertThat(task.get("task").get("state").asText()).isEqualTo("succeeded");
        assertThat(task.get("progress").asDouble()).isEqualTo(1.0);
        Map<String, JsonNode> byNo = byNo(task.get("task").get("results"));

        JsonNode audit = byNo.get("3.1");
        assertThat(audit.get("status").asText()).isEqualTo("matched");
        assertThat(texts(audit.get("bound_doc_ids"))).containsExactly("d_2031");
        JsonNode rejected = null;
        for (JsonNode c : audit.get("candidates")) {
            if (c.get("doc_id").asText().equals("d_2022")) {
                rejected = c;
            }
        }
        assertThat(rejected).isNotNull();
        assertThat(rejected.get("rejected").asBoolean()).isTrue();
        assertThat(texts(rejected.get("reject_reasons"))).contains("period_mismatch");

        JsonNode fs = byNo.get("4.2");
        assertThat(fs.get("status").asText()).isEqualTo("pending");
        assertThat(texts(fs.get("bound_doc_ids"))).isEmpty();
        assertThat(byNo.get("6.3").get("status").asText()).isEqualTo("missing");
        assertThat(byNo.get("7.1").get("status").asText()).isEqualTo("missing");
    }

    @Test
    void runUnknownChecklist404() throws Exception {
        assertThat(call(client, post("/matcher/checklists/cl_nope/run")).getStatus()).isEqualTo(404);
        assertThat(call(client, get("/matcher/tasks/tk_nope")).getStatus()).isEqualTo(404);
    }

    @Test
    void confirmSetsStatusAndAudit() throws Exception {
        JsonNode cl = upload(client);
        String checklistId = cl.get("checklist_id").asText();
        run(client, checklistId);
        String itemId = cl.get("items").get(1).get("item_id").asText(); // 4.2，机器判为 pending

        MockHttpServletResponse r = call(client, post("/matcher/items/" + itemId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"doc_ids\": [\"d_fs\"], \"note\": \"已核对为合并口径\"}".getBytes(StandardCharsets.UTF_8))
                .header("X-User-Id", "u_reviewer"));
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("status").asText()).isEqualTo("matched");
        assertThat(texts(body.get("bound_doc_ids"))).containsExactly("d_fs");
        assertThat(body.get("confirmed_by").asText()).isEqualTo("u_reviewer");

        List<AuditEvent> events = svc.store().listAudit(checklistId).stream()
                .filter(e -> itemId.equals(e.itemId())).toList();
        List<AuditEvent> confirm = events.stream().filter(e -> e.action().equals("item_confirmed")).toList();
        assertThat(confirm).hasSize(1);
        assertThat(confirm.get(0).operator()).isEqualTo("u_reviewer");
        assertThat(confirm.get(0).detail().get("from")).isEqualTo("pending");
        assertThat(confirm.get(0).detail().get("to")).isEqualTo("matched");
        assertThat(confirm.get(0).detail().get("doc_ids")).isEqualTo(List.of("d_fs"));
        assertThat(confirm.get(0).detail().get("note")).isEqualTo("已核对为合并口径");
        // 机器打分的状态变更也写了审计，操作人为 system
        assertThat(events).anyMatch(e -> e.action().equals("item_status_changed") && e.operator().equals("system"));

        // 重新匹配不覆盖人工确认
        JsonNode task = run(client, checklistId);
        JsonNode again = null;
        for (JsonNode x : task.get("task").get("results")) {
            if (x.get("item_id").asText().equals(itemId)) {
                again = x;
            }
        }
        assertThat(again).isNotNull();
        assertThat(again.get("status").asText()).isEqualTo("matched");
        assertThat(again.get("confirmed_by").asText()).isEqualTo("u_reviewer");
    }

    @Test
    void confirmEmptyDocIdsMarksMissing() throws Exception {
        JsonNode cl = upload(client);
        run(client, cl.get("checklist_id").asText());
        String itemId = cl.get("items").get(0).get("item_id").asText();
        JsonNode body = json(call(client, post("/matcher/items/" + itemId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"doc_ids\": [], \"note\": \"原件遗失\"}".getBytes(StandardCharsets.UTF_8))
                .header("X-User-Id", "u_reviewer")));
        assertThat(body.get("status").asText()).isEqualTo("missing");
        assertThat(texts(body.get("bound_doc_ids"))).isEmpty();
    }

    @Test
    void confirmRequiresOperatorAndExistingItem() throws Exception {
        JsonNode cl = upload(client);
        String itemId = cl.get("items").get(0).get("item_id").asText();
        assertThat(call(client, post("/matcher/items/" + itemId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"doc_ids\": [\"x\"]}")).getStatus())
                .isEqualTo(401);
        assertThat(call(client, post("/matcher/items/it_nope/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"doc_ids\": [\"x\"]}")
                .header("X-User-Id", "u")).getStatus())
                .isEqualTo(404);
    }

    @Test
    void manifestOnlyMatchedHaveFiles() throws Exception {
        JsonNode cl = upload(client);
        String checklistId = cl.get("checklist_id").asText();
        run(client, checklistId);
        MockHttpServletResponse r = call(client, get("/matcher/checklists/" + checklistId + "/manifest"));
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode m = json(r);
        assertThat(m.get("package_id").asText()).startsWith("pkg_").endsWith("_v1");
        assertThat(m.get("title").asText()).isEqualTo("测试授信资料包");
        assertThat(texts(m.get("outputs"))).containsExactly("zip", "merged_pdf", "checklist_xlsx");
        Map<String, JsonNode> entries = byNo(m.get("entries"));
        assertThat(entries.get("3.1")).isEqualTo(JSON.readTree(
                "{\"no\": \"3.1\", \"std_name\": \"审计报告\", \"status\": \"matched\", \"note\": \"\","
                        + " \"files\": [{\"uri\": \"oss://docs/d_2031.pdf\", \"pages\": null, \"order\": 1}]}"));
        assertThat(entries.get("4.2").get("status").asText()).isEqualTo("pending");
        assertThat(entries.get("4.2").get("files")).isEmpty();
        assertThat(entries.get("6.3").get("status").asText()).isEqualTo("missing");
        assertThat(entries.get("6.3").get("files")).isEmpty();
        assertThat(entries.get("7.1").get("std_name").asText()).isEqualTo("环评批复文件");
    }

    @Test
    void manifestBeforeRunAllMissing() throws Exception {
        JsonNode cl = upload(client);
        JsonNode m = json(call(client, get("/matcher/checklists/" + cl.get("checklist_id").asText() + "/manifest")));
        Set<String> statuses = new HashSet<>();
        m.get("entries").forEach(e -> statuses.add(e.get("status").asText()));
        assertThat(statuses).containsExactly("missing");
        assertThat(call(client, get("/matcher/checklists/cl_nope/manifest")).getStatus()).isEqualTo(404);
    }

    @Test
    void defaultServiceNeverAutoMatches() throws Exception {
        MockMvc mvc = clientFor(MatcherApplication.buildDefaultService());
        JsonNode cl = upload(mvc);
        JsonNode task = run(mvc, cl.get("checklist_id").asText());
        Set<String> statuses = new HashSet<>();
        task.get("task").get("results").forEach(x -> statuses.add(x.get("status").asText()));
        assertThat(statuses).containsExactly("missing");
    }

    @Test
    void taskFailureIsRecorded() throws Exception {
        MatcherService boom = new MatcherService(item -> {
            throw new RuntimeException("检索不可用");
        }, new FakeDecideProvider(), Fixtures.config());
        MockMvc mvc = clientFor(boom);
        JsonNode cl = upload(mvc);
        JsonNode task = run(mvc, cl.get("checklist_id").asText());
        assertThat(task.get("task").get("state").asText()).isEqualTo("failed");
        // Python 版断言异常类名 RuntimeError，Java 对应 RuntimeException
        assertThat(task.get("task").get("error").asText()).contains("RuntimeException");
    }

}
