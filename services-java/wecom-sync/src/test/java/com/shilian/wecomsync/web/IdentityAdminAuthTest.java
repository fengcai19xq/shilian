package com.shilian.wecomsync.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 管理端点默认必须带管理员令牌；/identity/me 不受影响。 */
@SpringBootTest(properties = {
        "WECOM_CORP_ID=ww-test",
        "WECOM_CORP_SECRET=dummy",
        "identity.require-admin-token=true",
        "identity.admin-token=test-admin",
})
@AutoConfigureMockMvc
class IdentityAdminAuthTest {

    @Autowired MockMvc mvc;

    @Test
    void syncWithoutAdminTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/identity/sync"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("admin_auth_failed"));
    }

    @Test
    void wrongAdminTokenIsRejected() throws Exception {
        mvc.perform(post("/identity/sync").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncWithAdminTokenWorks() throws Exception {
        mvc.perform(post("/identity/sync").header("X-Admin-Token", "test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("full"));
    }

    @Test
    void meDoesNotRequireAdminToken() throws Exception {
        mvc.perform(post("/identity/sync").header("X-Admin-Token", "test-admin"))
                .andExpect(status().isOk());
        mvc.perform(get("/identity/me").header("X-User-Id", "zhangsan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dept[0]").value("融资部"));
    }
}
