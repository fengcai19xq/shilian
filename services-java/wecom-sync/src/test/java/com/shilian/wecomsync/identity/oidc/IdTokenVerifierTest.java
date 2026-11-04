package com.shilian.wecomsync.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * WK17 统一登录：id_token 验签与声明校验。
 * 重点覆盖「客户端能自报的一切东西」——算法、受众、有效期都可能被伪造。
 */
class IdTokenVerifierTest {

    private static final String ISS = "https://idp.internal";
    private static final String CLIENT = "shilian-web";
    private static final String SECRET = "test-hmac-secret-please-32b!";

    private static OidcProperties hsProps(String secret) {
        return new OidcProperties(true, ISS, CLIENT, "HS256", secret, null, "sub", 60);
    }

    private static OidcProperties rsProps(String pem) {
        return new OidcProperties(true, ISS, CLIENT, "RS256", null, pem, "sub", 60);
    }

    @Test
    void acceptsTokenSignedWithConfiguredSecret() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.hs256(SECRET, claims("zhangsan", ISS, CLIENT, plus(300)));

        JsonNode claims = v.verify(token);

        assertThat(claims.path("sub").asText()).isEqualTo("zhangsan");
        assertThat(v.userIdOf(claims)).contains("zhangsan");
    }

    @Test
    void rejectsSignatureFromOtherSecret() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.hs256("another-secret-value-32-bytes!!!",
                claims("zhangsan", ISS, CLIENT, plus(300)));

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 签名校验失败");
    }

    /** 红线：alg=none 是 JWT 最经典的绕过，必须拒。 */
    @Test
    void rejectsAlgNone() {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.unsigned(claims("zhangsan", ISS, CLIENT, plus(300)));

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 算法不符合预期");
    }

    /** 红线：算法混淆——攻击者拿 RS 公钥当 HMAC 密钥签名，配置写 RS256 时不能认。 */
    @Test
    void rejectsAlgorithmConfusionEvenWhenSignatureIsValid() throws Exception {
        var kp = TestJwt.newKeyPair();
        IdTokenVerifier v = new IdTokenVerifier(rsProps(TestJwt.toPem(kp.getPublic())));
        String forged = TestJwt.hmacWith(kp.getPublic(), claims("zhangsan", ISS, CLIENT, plus(300)));

        assertThatThrownBy(() -> v.verify(forged))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 算法不符合预期");
    }

    /** 反向混淆：配置写 HS256 时，也不能认 RS256 签名的 token。 */
    @Test
    void rejectsRs256TokenWhenHs256Configured() throws Exception {
        var kp = TestJwt.newKeyPair();
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.rs256(kp.getPrivate(), claims("zhangsan", ISS, CLIENT, plus(300)));

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 算法不符合预期");
    }

    @Test
    void acceptsRs256WithConfiguredPublicKey() throws Exception {
        var kp = TestJwt.newKeyPair();
        IdTokenVerifier v = new IdTokenVerifier(rsProps(TestJwt.toPem(kp.getPublic())));

        JsonNode claims = v.verify(
                TestJwt.rs256(kp.getPrivate(), claims("zhangsan", ISS, CLIENT, plus(300))));

        assertThat(v.userIdOf(claims)).contains("zhangsan");
    }

    /** 换过公钥之后的 RS256 token 必须验不过。 */
    @Test
    void rejectsRs256SignedByOtherKey() throws Exception {
        var kp = TestJwt.newKeyPair();
        IdTokenVerifier v = new IdTokenVerifier(rsProps(TestJwt.toPem(TestJwt.newKeyPair().getPublic())));
        String token = TestJwt.rs256(kp.getPrivate(), claims("zhangsan", ISS, CLIENT, plus(300)));

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 签名校验失败");
    }

    /** 算法名大小写不敏感：header 写 rs256 也应认可（真实 IdP 偶有此写法）。 */
    @Test
    void acceptsLowercaseAlgHeader() throws Exception {
        var kp = TestJwt.newKeyPair();
        IdTokenVerifier v = new IdTokenVerifier(rsProps(TestJwt.toPem(kp.getPublic())));
        String token = TestJwt.rs256(kp.getPrivate(), "rs256", claims("zhangsan", ISS, CLIENT, plus(300)));

        assertThat(v.verify(token).path("sub").asText()).isEqualTo("zhangsan");
    }

    @Test
    void rejectsExpiredButToleratesConfiguredSkew() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String expired = TestJwt.hs256(SECRET, claims("zhangsan", ISS, CLIENT, minus(3600)));
        assertThatThrownBy(() -> v.verify(expired))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 已过期");

        // 落在 60s 容忍窗口内应放行，避免多机时钟漂移误伤
        String slightPast = TestJwt.hs256(SECRET, claims("zhangsan", ISS, CLIENT, minus(30)));
        assertThat(v.verify(slightPast).path("sub").asText()).isEqualTo("zhangsan");
    }

    @Test
    void rejectsNotYetValidToken() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String payload = "{\"sub\":\"zhangsan\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d,\"nbf\":%d}"
                .formatted(ISS, CLIENT, plus(7200), plus(3600));
        String token = TestJwt.hs256(SECRET, payload);

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 尚未生效");
    }

    @Test
    void rejectsWrongIssuerAndWrongAudience() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));

        assertThatThrownBy(() -> v.verify(
                TestJwt.hs256(SECRET, claims("zhangsan", "https://evil", CLIENT, plus(300)))))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 签发者不匹配");

        assertThatThrownBy(() -> v.verify(
                TestJwt.hs256(SECRET, claims("zhangsan", ISS, "other-app", plus(300)))))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 受众不匹配");
    }

    /** 缺 iss 声明同样是不匹配，不能当成放行。 */
    @Test
    void rejectsTokenWithoutIssuer() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.hs256(SECRET,
                "{\"sub\":\"zhangsan\",\"aud\":\"%s\",\"exp\":%d}".formatted(CLIENT, plus(300)));

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 签发者不匹配");
    }

    /** aud 常见两种写法，都必须认，否则接真实 IdP 直接全拒。 */
    @Test
    void acceptsAudienceAsArrayContainingClientId() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.hs256(SECRET, """
                {"sub":"zhangsan","iss":"%s","aud":["other-app","%s"],"exp":%d}
                """.formatted(ISS, CLIENT, plus(300)));

        assertThat(v.verify(token).path("sub").asText()).isEqualTo("zhangsan");
    }

    @Test
    void rejectsAudienceArrayWithoutClientId() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.hs256(SECRET, """
                {"sub":"zhangsan","iss":"%s","aud":["other-app","third-app"],"exp":%d}
                """.formatted(ISS, plus(300)));

        assertThatThrownBy(() -> v.verify(token))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 受众不匹配");
    }

    /** 错误信息不得回显 token 内容——它是可复用凭证。 */
    @Test
    void failureMessageNeverLeaksTokenMaterial() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String token = TestJwt.hs256(SECRET, claims("zhangsan", ISS, CLIENT, plus(300)));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> v.verify(tampered))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 签名校验失败")
                .hasMessageNotContaining(token)
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("zhangsan");
    }

    /** 配置写错要当场炸，不能悄悄降级成「谁都能过」。 */
    @Test
    void missingKeyMaterialFailsFastAtConstruction() {
        assertThatThrownBy(() -> new IdTokenVerifier(hsProps(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oidc.hmac-secret");
        assertThatThrownBy(() -> new IdTokenVerifier(hsProps("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oidc.hmac-secret");

        assertThatThrownBy(() -> new IdTokenVerifier(rsProps(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oidc.public-key-pem");

        assertThatThrownBy(() -> new IdTokenVerifier(
                new OidcProperties(true, ISS, CLIENT, "ES256", SECRET, null, "sub", 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持 HS256 / RS256");

        assertThatThrownBy(() -> new IdTokenVerifier(rsProps("not-a-pem")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X.509 RSA 公钥");
    }

    @Test
    void rejectsMalformedInput() {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));

        assertThatThrownBy(() -> v.verify(null))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("缺少 id_token");
        assertThatThrownBy(() -> v.verify("  "))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class);
        assertThatThrownBy(() -> v.verify("a.b"))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class);
        assertThatThrownBy(() -> v.verify("a.b.c.d"))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 格式无效");
        assertThatThrownBy(() -> v.verify("!!!.###.%%%"))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token 段不是合法 base64url");
    }

    /** header 不是 JSON：只报解析失败，不带出底层异常细节。 */
    @Test
    void rejectsGarbageHeaderWithoutInternalDetail() {
        IdTokenVerifier v = new IdTokenVerifier(hsProps(SECRET));
        String bogusHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nope".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> v.verify(bogusHeader + ".e30.c2ln"))
                .isInstanceOf(IdTokenVerifier.InvalidTokenException.class)
                .hasMessage("id_token header 解析失败");
    }

    @Test
    void userIdClaimIsConfigurable() throws Exception {
        IdTokenVerifier v = new IdTokenVerifier(
                new OidcProperties(true, ISS, CLIENT, "HS256", SECRET, null, "wecom_userid", 60));
        String token = TestJwt.hs256(SECRET, """
                {"sub":"someone-else","wecom_userid":"zhangsan","iss":"%s","aud":"%s","exp":%d}
                """.formatted(ISS, CLIENT, plus(300)));

        assertThat(v.userIdOf(v.verify(token))).contains("zhangsan");
    }

    // ---- helpers ----

    private static long plus(int seconds) {
        return Instant.now().getEpochSecond() + seconds;
    }

    private static long minus(int seconds) {
        return Instant.now().getEpochSecond() - seconds;
    }

    private static String claims(String sub, String iss, String aud, long exp) {
        return "{\"sub\":\"%s\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d}".formatted(sub, iss, aud, exp);
    }
}
