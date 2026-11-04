package com.shilian.wecomsync.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shilian.wecomsync.identity.oidc.TestJwt;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WK17 统一登录接通后的端到端行为：身份只认 IdP 签发的 id_token。
 *
 * 单独一个上下文，因为 oidc.enabled=true 会改变所有请求的身份来源，
 * 与过渡模式的用例必须分开验证。
 */
@SpringBootTest(properties = {
        "WECOM_CORP_ID=ww-test",
        "WECOM_CORP_SECRET=dummy",
        "oidc.enabled=true",
        "oidc.issuer=https://idp.internal",
        "oidc.client-id=shilian-web",
        "oidc.algorithm=HS256",
        "oidc.hmac-secret=test-hmac-secret-please-32b!",
})
@AutoConfigureMockMvc
class IdentityMeOidcTest {

    private static final String ISS = "https://idp.internal";
    private static final String CLIENT = "shilian-web";
    private static final String SECRET = "test-hmac-secret-please-32b!";

    @Autowired MockMvc mvc;

    @BeforeEach
    void seed() throws Exception {
        mvc.perform(post("/identity/sync")).andExpect(status().isOk());
    }

    private static String token(String sub, long exp) throws Exception {
        return TestJwt.hs256(SECRET,
                "{\"sub\":\"%s\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d}".formatted(sub, ISS, CLIENT, exp));
    }

    private static long inFuture(int seconds) {
        return Instant.now().getEpochSecond() + seconds;
    }

    @Test
    void validTokenResolvesThatUsersPrincipal() throws Exception {
        mvc.perform(get("/identity/me").header("Authorization", "Bearer " + token("zhangsan", inFuture(300))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dept[0]").value("融资部"));
    }

    /** 红线：开关打开后，光靠 X-User-Id 自报身份不再管用。 */
    @Test
    void headerAloneNoLongerAuthenticates() throws Exception {
        mvc.perform(get("/identity/me").header("X-User-Id", "zhangsan"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    /** 红线：token 与请求头冲突时以 token 为准，请求头不能提权。 */
    @Test
    void tokenWinsOverHeaderAndCannotBeEscalated() throws Exception {
        mvc.perform(get("/identity/me")
                        .header("Authorization", "Bearer " + token("zhangsan", inFuture(300)))
                        .header("X-User-Id", "wangwu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dept[0]").value("融资部"));
    }

    @Test
    void forgedOrExpiredTokenIs401() throws Exception {
        String claims = "{\"sub\":\"zhangsan\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d}"
                .formatted(ISS, CLIENT, inFuture(300));
        // 声明完全一样，只是换成攻击者猜的密钥签名
        mvc.perform(get("/identity/me").header("Authorization",
                "Bearer " + TestJwt.hs256("attacker-guessed-secret-32-bytes!!", claims)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/identity/me").header("Authorization",
                "Bearer " + token("zhangsan", Instant.now().getEpochSecond() - 7200)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/identity/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /** 红线：alg=none 伪造的 admin 身份必须被拒。 */
    @Test
    void unsignedTokenIsRejected() throws Exception {
        String forged = TestJwt.unsigned(
                "{\"sub\":\"zhangsan\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d}".formatted(ISS, CLIENT, inFuture(300)));

        mvc.perform(get("/identity/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    /** 有效 token 但用户不在本地目录：404，且 401/404 语义要分得开。 */
    @Test
    void validTokenForUnknownUserIs404() throws Exception {
        mvc.perform(get("/identity/me").header("Authorization", "Bearer " + token("ghost", inFuture(300))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    /** 其他管理端点仍可用（投影、授权写操作走管理面，不受本次改造影响）。 */
    @Test
    void managementEndpointsStillMounted() throws Exception {
        mvc.perform(get("/identity/principal/zhangsan")).andExpect(status().isOk());
        mvc.perform(post("/identity/weknora/project").param("dry_run", "true")).andExpect(status().isOk());
    }
}
