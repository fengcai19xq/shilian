package com.shilian.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.shilian.matcher.config.MatcherJson;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 额外用例（Python 版无对应）：Spring 上下文装配可启动，且对外 JSON 为 snake_case。 */
@SpringBootTest
@AutoConfigureMockMvc
class MatcherApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void contextWiresSnakeCaseJson() throws Exception {
        MvcResult r = mvc.perform(post("/matcher/checklists")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("text", "3.1 2023年度审计报告（合并口径，加盖公章）")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = MatcherJson.mapper().readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.has("checklist_id")).isTrue();
        JsonNode item = body.get("items").get(0);
        assertThat(item.has("item_id")).isTrue();
        assertThat(item.get("constraints").get("stamp_required").asBoolean()).isTrue();
    }
}
