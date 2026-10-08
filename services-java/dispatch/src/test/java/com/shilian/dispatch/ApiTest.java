package com.shilian.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.dispatch.config.DispatchJson;
import com.shilian.dispatch.support.Fixtures;
import com.shilian.dispatch.web.DispatchController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 接口层：snake_case 字段、状态码、错误体 {"detail"} 与 X-User-Id 操作人。 */
class ApiTest {

    static final ObjectMapper JSON = DispatchJson.mapper();
    static final String CREATE_BODY = """
            {"checklist_id": "cl_0007", "items": [
              {"item_id": "it_0031", "no": "3.1", "raw_text": "最近三年审计报告（合并口径，加盖公章）",
               "std_type": "AUDIT_REPORT", "status": "missing",
               "constraints": {"period": ["2021", "2022", "2023"], "scope": "consolidated"}},
              {"item_id": "it_0072", "std_type": "OTHER", "status": "pending", "period": ["2024"],
               "owner_dept": "legal"}
            ]}
            """;

    Fixtures f;
    MockMvc client;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        client = MockMvcBuilders.standaloneSetup(new DispatchController(f.service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(DispatchJson.mapper()))
                .build();
    }

    record Resp(int status, JsonNode body) {
    }

    Resp call(RequestBuilder rb) throws Exception {
        MockHttpServletResponse r = client.perform(rb).andReturn().getResponse();
        String s = r.getContentAsString(StandardCharsets.UTF_8);
        return new Resp(r.getStatus(), s.isEmpty() ? null : JSON.readTree(s));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body, String user) {
        b.contentType(MediaType.APPLICATION_JSON).content(body);
        return user == null ? b : b.header("X-User-Id", user);
    }

    Resp create() throws Exception {
        return call(json(post("/dispatch/tickets"), CREATE_BODY, "u_pm"));
    }

    @Test
    void createReturns201WithSnakeCaseTickets() throws Exception {
        Resp r = create();
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("checklist_id").asText()).isEqualTo("cl_0007");
        JsonNode t0 = r.body().get("created").get(0);
        assertThat(t0.get("ticket_id").asText()).isEqualTo("tk_000001");
        assertThat(t0.get("status").asText()).isEqualTo("assigned");
        assertThat(t0.get("assignee").asText()).isEqualTo("u_fin_audit");
        assertThat(t0.get("dept_head").asText()).isEqualTo("u_fin_head");
        assertThat(t0.get("period").toString()).isEqualTo("[\"2021\",\"2022\",\"2023\"]");
        assertThat(t0.get("due_at").asText()).isEqualTo("2026-10-10T00:00:00Z");
        JsonNode t1 = r.body().get("created").get(1);
        assertThat(t1.get("routed_by").asText()).isEqualTo("owner_dept");
        assertThat(t1.get("item_status").asText()).isEqualTo("pending");
        assertThat(r.body().get("skipped").size()).isZero();

        Resp again = create();
        assertThat(again.body().get("created").size()).isZero();
        assertThat(again.body().get("skipped").get(0).get("reason").asText()).isEqualTo("active_ticket_exists");
    }

    @Test
    void createValidationErrors() throws Exception {
        Resp bad = call(json(post("/dispatch/tickets"),
                "{\"checklist_id\":\"cl_0007\",\"items\":[{\"item_id\":\"a\",\"std_type\":\"X\",\"status\":\"matched\"}]}",
                null));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("detail").asText()).contains("missing 或 pending");
        assertThat(call(json(post("/dispatch/tickets"), "not json", null)).status()).isEqualTo(400);
    }

    @Test
    void listByChecklist() throws Exception {
        create();
        Resp r = call(get("/dispatch/tickets").param("checklist_id", "cl_0007"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("total").asInt()).isEqualTo(2);
        assertThat(r.body().get("tickets").get(0).get("item_id").asText()).isEqualTo("it_0031");
        Resp filtered = call(get("/dispatch/tickets").param("checklist_id", "cl_0007").param("status", "uploaded"));
        assertThat(filtered.body().get("total").asInt()).isZero();
        assertThat(call(get("/dispatch/tickets")).status()).isEqualTo(422);
        assertThat(call(get("/dispatch/tickets").param("checklist_id", "cl_0007").param("status", "x")).status())
                .isEqualTo(422);
    }

    @Test
    void callbackAcceptFlowWithAuditEvents() throws Exception {
        create();
        Resp up = call(json(post("/dispatch/tickets/tk_000001/callback"),
                "{\"doc_id\":\"d_audit_2023\",\"note\":\"扫描件\"}", "u_fin_audit"));
        assertThat(up.status()).isEqualTo(200);
        assertThat(up.body().get("status").asText()).isEqualTo("uploaded");
        assertThat(up.body().get("doc_ids").get(0).asText()).isEqualTo("d_audit_2023");
        assertThat(up.body().get("last_doc_id").asText()).isEqualTo("d_audit_2023");

        Resp ok = call(json(post("/dispatch/tickets/tk_000001/accept"), "{\"note\":\"无误\"}", "u_pm"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("status").asText()).isEqualTo("accepted");
        assertThat(ok.body().get("reviewed_by").asText()).isEqualTo("u_pm");

        Resp ev = call(get("/dispatch/tickets/tk_000001/events"));
        assertThat(ev.body().size()).isEqualTo(4);
        JsonNode last = ev.body().get(3);
        assertThat(last.get("action").asText()).isEqualTo("accepted");
        assertThat(last.get("from_status").asText()).isEqualTo("uploaded");
        assertThat(last.get("to_status").asText()).isEqualTo("accepted");
        assertThat(last.get("operator").asText()).isEqualTo("u_pm");
        assertThat(last.get("event_id").asText()).startsWith("ev_");

        assertThat(call(get("/dispatch/tickets/tk_000001")).body().get("status").asText()).isEqualTo("accepted");
    }

    @Test
    void callbackErrors() throws Exception {
        create();
        String body = "{\"doc_id\":\"d_audit_2023\"}";
        assertThat(call(json(post("/dispatch/tickets/tk_404/callback"), body, "u_fin_audit")).status())
                .isEqualTo(404);
        Resp forbidden = call(json(post("/dispatch/tickets/tk_000001/callback"), body, "u_someone"));
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.body().get("detail").asText()).contains("负责人");
        assertThat(call(json(post("/dispatch/tickets/tk_000001/callback"), "{\"doc_id\":\"d_ghost\"}",
                "u_fin_audit")).status()).isEqualTo(422);
        assertThat(call(json(post("/dispatch/tickets/tk_000001/callback"), "{}", "u_fin_audit")).status())
                .isEqualTo(422);
    }

    @Test
    void rejectRequiresReasonAndUploadedState() throws Exception {
        create();
        Resp early = call(json(post("/dispatch/tickets/tk_000001/reject"), "{\"reason\":\"x\"}", "u_pm"));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().get("detail").asText()).contains("assigned");
        call(json(post("/dispatch/tickets/tk_000001/callback"), "{\"doc_id\":\"d_audit_2023\"}", "u_fin_audit"));
        assertThat(call(post("/dispatch/tickets/tk_000001/reject").header("X-User-Id", "u_pm")).status())
                .isEqualTo(422);
        Resp r = call(json(post("/dispatch/tickets/tk_000001/reject"), "{\"reason\":\"缺少公章\"}", "u_pm"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("rejected");
        assertThat(r.body().get("reject_reason").asText()).isEqualTo("缺少公章");
    }

    @Test
    void acceptWithoutBodyBeforeUploadIsConflict() throws Exception {
        create();
        assertThat(call(post("/dispatch/tickets/tk_000001/accept")).status()).isEqualTo(409);
        assertThat(call(post("/dispatch/tickets/tk_404/accept")).status()).isEqualTo(404);
    }

    @Test
    void escalationEndpoint() throws Exception {
        create();
        assertThat(call(post("/dispatch/escalations/run")).body().get("total").asInt()).isZero();
        f.clock.advance(Duration.ofHours(48));
        Resp r = call(post("/dispatch/escalations/run"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("total").asInt()).isEqualTo(2);
        assertThat(r.body().get("escalated").get(0).get("escalation_count").asInt()).isEqualTo(1);
    }
}
