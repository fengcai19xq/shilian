package com.shilian.wecomsync.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WK17：会话身份来源。
 * 核心断言是「oidc 打开时，请求头里的自报身份一律不算数」。
 */
class SessionResolverTest {

    private static final String ISS = "https://idp.internal";
    private static final String CLIENT = "shilian-web";
    private static final String SECRET = "test-hmac-secret-please-32b!";

    private static OidcProperties props(boolean enabled) {
        return new OidcProperties(enabled, ISS, CLIENT, "HS256", SECRET, null, "sub", 60);
    }

    private static SessionResolver on(IdTokenVerifier v) {
        return new SessionResolver(props(true), Optional.of(v));
    }

    private static SessionResolver off() {
        return new SessionResolver(props(false), Optional.empty());
    }

    @Test
    void transitionalModeReadsGatewayInjectedHeader() {
        assertThat(off().resolveWecomUserId(null, " zhangsan ")).isEqualTo("zhangsan");

        assertThatThrownBy(() -> off().resolveWecomUserId(null, null))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("缺少会话身份");
        assertThatThrownBy(() -> off().resolveWecomUserId(null, "   "))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void oidcModeResolvesUserIdFromSignedToken() throws Exception {
        String token = TestJwt.hs256(SECRET, claims("zhangsan", plus(300)));

        assertThat(on(new IdTokenVerifier(props(true)))
                .resolveWecomUserId("Bearer " + token, "attacker-controlled"))
                .isEqualTo("zhangsan");
    }

    /** 红线：开关打开后，X-User-Id 自报身份必须无效（上面用例已验证不受影响，这里补强：根本没带 token 也不许退回请求头）。 */
    @Test
    void oidcModeNeverFallsBackToHeader() {
        SessionResolver r = on(new IdTokenVerifier(props(true)));

        assertThatThrownBy(() -> r.resolveWecomUserId(null, "zhangsan"))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("缺少 id_token");
        assertThatThrownBy(() -> r.resolveWecomUserId("", "zhangsan"))
                .isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> r.resolveWecomUserId("Basic dXNlcjpwYXNz", "zhangsan"))
                .isInstanceOf(UnauthenticatedException.class);
    }

    /** 验签失败对外只说「会话凭证无效」，不带出验签层的具体原因。 */
    @Test
    void oidcModeCollapsesAllVerificationFailures() throws Exception {
        SessionResolver r = on(new IdTokenVerifier(props(true)));
        String expired = TestJwt.hs256(SECRET, claims("zhangsan", minus(3600)));
        String wrongKey = TestJwt.hs256("different-secret-value-32bytes!", claims("zhangsan", plus(300)));

        assertThatThrownBy(() -> r.resolveWecomUserId("Bearer " + expired, null))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("会话凭证无效")
                .hasMessageNotContaining("过期");
        assertThatThrownBy(() -> r.resolveWecomUserId("Bearer " + wrongKey, null))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("会话凭证无效");
    }

    /** 签名有效但没带身份 claim：不能当成匿名用户放行。 */
    @Test
    void oidcModeRejectsTokenWithoutUserIdClaim() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(props(true));
        String token = TestJwt.hs256(SECRET, """
                {"iss":"%s","aud":"%s","exp":%d}
                """.formatted(ISS, CLIENT, plus(300)));

        assertThatThrownBy(() -> on(v).resolveWecomUserId("Bearer " + token, null))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("id_token 缺少身份声明");
    }

    /** 开关打开但验签器没装上（配置漏了密钥等）：拒绝，不退化成信任请求头。 */
    @Test
    void oidcModeWithoutVerifierRefusesInsteadOfDegrading() {
        SessionResolver r = new SessionResolver(props(true), Optional.empty());

        assertThatThrownBy(() -> r.resolveWecomUserId("Bearer whatever", "zhangsan"))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("统一登录未正确配置");
    }

    @Test
    void bearerParsingToleratesCaseAndWhitespaceButNothingElse() {
        assertThat(SessionResolver.bearerTokenOf("Bearer abc")).isEqualTo("abc");
        assertThat(SessionResolver.bearerTokenOf("bearer   abc  ")).isEqualTo("abc");
        assertThat(SessionResolver.bearerTokenOf("BeArEr abc")).isEqualTo("abc");
        assertThat(SessionResolver.bearerTokenOf(null)).isEmpty();
        assertThat(SessionResolver.bearerTokenOf("")).isEmpty();
        assertThat(SessionResolver.bearerTokenOf("Bearer")).isEmpty();
        assertThat(SessionResolver.bearerTokenOf("Token abc")).isEmpty();
        assertThat(SessionResolver.bearerTokenOf("abc")).isEmpty();
    }

    // ---- helpers ----

    private static long plus(int seconds) {
        return Instant.now().getEpochSecond() + seconds;
    }

    private static long minus(int seconds) {
        return Instant.now().getEpochSecond() - seconds;
    }

    private static String claims(String sub, long exp) {
        return "{\"sub\":\"%s\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d}".formatted(sub, ISS, CLIENT, exp);
    }
}
