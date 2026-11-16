package com.shilian.wecomsync.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 未配置管理员令牌时管理端点必须 fail-closed，而不是静默放行。 */
@SpringBootTest(properties = {
        "WECOM_CORP_ID=ww-test",
        "WECOM_CORP_SECRET=dummy",
        "identity.require-admin-token=true",
})
@AutoConfigureMockMvc
class IdentityAdminAuthNotConfiguredTest {

    @Autowired MockMvc mvc;

    @Test
    void managementEndpointIsForbiddenWhenTokenNotConfigured() throws Exception {
        mvc.perform(post("/identity/sync"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("admin_auth_not_configured"));
    }
}
