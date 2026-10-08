package com.shilian.wecomsync.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.wecomsync.audit.InMemoryAuditSink;
import com.shilian.wecomsync.config.WecomProperties;
import com.shilian.wecomsync.store.InMemoryDepartmentRepository;
import com.shilian.wecomsync.store.InMemoryGrantRepository;
import com.shilian.wecomsync.store.InMemoryUserRepository;
import com.shilian.wecomsync.wecom.FakeWecomClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"WECOM_CORP_ID=ww-test", "WECOM_CORP_SECRET=dummy"})
@AutoConfigureMockMvc
class IdentityControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FakeWecomClient wecom;
    @Autowired InMemoryUserRepository users;
    @Autowired InMemoryDepartmentRepository depts;
    @Autowired InMemoryGrantRepository grants;
    @Autowired InMemoryAuditSink audit;
    @Autowired WecomProperties props;

    @BeforeEach
    void reset() {
        wecom.loadDemo();
        users.clear();
        depts.clear();
        grants.clear();
        audit.clear();
    }

    private void fullSync() throws Exception {
        mvc.perform(post("/identity/sync")).andExpect(status().isOk());
    }

    @Test
    void syncWithoutBodyDefaultsToFull() throws Exception {
        mvc.perform(post("/identity/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("full"))
                .andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.left").value(0))
                .andExpect(jsonPath("$.departments").value(5))
                .andExpect(jsonPath("$.audit_events").value(3))
                .andExpect(jsonPath("$.synced_at").exists());
    }

    @Test
    void incrementalSyncReportsLeft() throws Exception {
        fullSync();
        wecom.removeMember("lisi");
        mvc.perform(post("/identity/sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"incremental\",\"user_ids\":[\"lisi\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("incremental"))
                .andExpect(jsonPath("$.left").value(1));
    }

    @Test
    void syncBadRequests() throws Exception {
        mvc.perform(post("/identity/sync").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mvc.perform(post("/identity/sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"incremental\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/identity/sync").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void syncWecomFailureIs502() throws Exception {
        wecom.setFailing(true);
        mvc.perform(post("/identity/sync"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("wecom_unavailable"));
    }

    @Test
    void syncEmptySnapshotIs409() throws Exception {
        fullSync();
        wecom.reset();
        mvc.perform(post("/identity/sync"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("empty_snapshot"));
    }

    @Test
    void principalMatchesRetrievalContractShape() throws Exception {
        fullSync();
        String body = mvc.perform(get("/identity/principal/zhangsan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(startsWith("u_")))
                .andExpect(jsonPath("$.dept[0]").value("融资部"))
                .andExpect(jsonPath("$.dept[1]").value("财务共享"))
                .andExpect(jsonPath("$.roles", hasSize(0)))
                .andExpect(jsonPath("$.max_sensitivity").value("L2"))
                .andExpect(jsonPath("$.projects", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        org.assertj.core.api.Assertions.assertThat(keys)
                .containsExactly("user_id", "dept", "roles", "max_sensitivity", "projects");
    }

    @Test
    void principalUnknownIs404AndLeftIs410() throws Exception {
        fullSync();
        mvc.perform(get("/identity/principal/nobody"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        wecom.removeMember("lisi");
        fullSync();
        mvc.perform(get("/identity/principal/lisi"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("principal_inactive"));
    }

    @Test
    void grantUpdatesPrincipalAndAudit() throws Exception {
        fullSync();
        mvc.perform(put("/identity/grants/zhangsan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"financing_staff\"],\"max_sensitivity\":\"L3\","
                                + "\"projects\":[\"PRJ-2026-CITIC\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.max_sensitivity").value("L3"))
                .andExpect(jsonPath("$.roles[0]").value("financing_staff"));
        mvc.perform(get("/identity/principal/zhangsan"))
                .andExpect(jsonPath("$.projects[0]").value("PRJ-2026-CITIC"));
        mvc.perform(get("/identity/audit-events").param("wecom_user_id", "zhangsan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].event_type").value("principal_created"))
                .andExpect(jsonPath("$.events[1].event_type").value("principal_changed"))
                .andExpect(jsonPath("$.events[1].source").value("grant"))
                .andExpect(jsonPath("$.events[1].changes.max_sensitivity.before").value("L2"))
                .andExpect(jsonPath("$.events[1].changes.max_sensitivity.after").value("L3"));
    }

    @Test
    void grantInvalidSensitivityIs400() throws Exception {
        fullSync();
        mvc.perform(put("/identity/grants/zhangsan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_sensitivity\":\"L9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/identity/grants/nobody").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_sensitivity\":\"L2\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void auditEventsListsAll() throws Exception {
        fullSync();
        mvc.perform(get("/identity/audit-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[0].wecom_user_id").exists())
                .andExpect(jsonPath("$.events[0].user_id").exists())
                .andExpect(jsonPath("$.events[0].ts").exists());
    }

    @Test
    void credentialsBoundFromEnvironment() {
        org.assertj.core.api.Assertions.assertThat(props.corpId()).isEqualTo("ww-test");
        org.assertj.core.api.Assertions.assertThat(props.configured()).isTrue();
    }
}
