package com.shilian.wecomsync.identity.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 校验 IdP 签发的 id_token 并返回其声明（WK17 统一登录）。纯验签，不签发也不续签。
 *
 * 安全约束（缺一不可）：
 *  - 只接受配置指定的算法，显式拒绝 alg=none 与 HS/RS 算法混淆；
 *  - 校验 iss / aud / exp / nbf，容忍可配置时钟偏移；
 *  - 失败只回笼统原因，不回显 token、不回显期望值，调用方也不要写进日志。
 */
public class IdTokenVerifier {

    private static final String HS256 = "HS256";
    private static final String RS256 = "RS256";

    /** 令牌无效。message 为固定文案，不含 token 内容与期望值。 */
    public static class InvalidTokenException extends RuntimeException {
        InvalidTokenException(String reason) {
            super(reason);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final OidcProperties props;
    private final String algorithm;
    private final byte[] hmacKey;
    private final PublicKey rsaPublicKey;

    public IdTokenVerifier(OidcProperties props) {
        this.props = props;
        this.algorithm = normalizeAlg(props.algorithm());
        if (HS256.equals(algorithm)) {
            if (props.hmacSecret() == null || props.hmacSecret().isBlank()) {
                throw new IllegalArgumentException("oidc.algorithm=HS256 时必须配置 oidc.hmac-secret");
            }
            this.hmacKey = props.hmacSecret().getBytes(StandardCharsets.UTF_8);
            this.rsaPublicKey = null;
        } else if (RS256.equals(algorithm)) {
            if (props.publicKeyPem() == null || props.publicKeyPem().isBlank()) {
                throw new IllegalArgumentException("oidc.algorithm=RS256 时必须配置 oidc.public-key-pem");
            }
            this.hmacKey = null;
            this.rsaPublicKey = loadPublicKey(props.publicKeyPem());
        } else {
            throw new IllegalArgumentException("oidc.algorithm 只支持 HS256 / RS256，收到: " + props.algorithm());
        }
    }

    /** 验签并校验标准声明；通过后返回 payload。 */
    public JsonNode verify(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new InvalidTokenException("缺少 id_token");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidTokenException("id_token 格式无效");
        }

        JsonNode header = readJson(decode(parts[0]), "header");
        // 先比算法再做密码学校验：拒绝 none，也堵住 HS/RS 混淆
        if (!algorithm.equals(normalizeAlg(header.path("alg").asText("")))) {
            throw new InvalidTokenException("id_token 算法不符合预期");
        }

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!signatureMatches(signingInput, decode(parts[2]))) {
            throw new InvalidTokenException("id_token 签名校验失败");
        }

        JsonNode claims = readJson(decode(parts[1]), "payload");
        checkClaims(claims);
        return claims;
    }

    /** 从已校验的声明中取企微 userid 所在 claim。 */
    public Optional<String> userIdOf(JsonNode claims) {
        String value = claims.path(props.userIdClaim()).asText("");
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    // ---- 签名 ----

    private boolean signatureMatches(byte[] signingInput, byte[] signature) {
        try {
            if (HS256.equals(algorithm)) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
                // 定长比较，避免按字节短路造成的时序差异
                return MessageDigest.isEqual(mac.doFinal(signingInput), signature);
            }
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(rsaPublicKey);
            sig.update(signingInput);
            return sig.verify(signature);
        } catch (Exception e) {
            // 不把底层异常信息带出去，避免泄露密钥/算法细节
            return false;
        }
    }

    // ---- 声明 ----

    private void checkClaims(JsonNode claims) {
        long skew = Math.max(0, props.clockSkewSeconds());
        long now = Instant.now().getEpochSecond();

        if (props.issuer() == null || props.issuer().isBlank()
                || !props.issuer().equals(claims.path("iss").asText(""))) {
            throw new InvalidTokenException("id_token 签发者不匹配");
        }
        if (!audienceMatches(claims)) {
            throw new InvalidTokenException("id_token 受众不匹配");
        }
        if (claims.has("exp") && now > claims.path("exp").asLong() + skew) {
            throw new InvalidTokenException("id_token 已过期");
        }
        if (claims.has("nbf") && now < claims.path("nbf").asLong() - skew) {
            throw new InvalidTokenException("id_token 尚未生效");
        }
    }

    /** aud 允许字符串或数组（不同 IdP 写法不一）。 */
    private boolean audienceMatches(JsonNode claims) {
        String want = props.clientId() == null ? "" : props.clientId();
        JsonNode aud = claims.path("aud");
        if (aud.isArray()) {
            for (JsonNode a : aud) {
                if (want.equals(a.asText(""))) {
                    return true;
                }
            }
            return false;
        }
        return want.equals(aud.asText(""));
    }

    // ---- 工具 ----

    private JsonNode readJson(byte[] raw, String what) {
        try {
            return mapper.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new InvalidTokenException("id_token " + what + " 解析失败");
        }
    }

    private static byte[] decode(String segment) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("id_token 段不是合法 base64url");
        }
    }

    private static String normalizeAlg(String alg) {
        return alg == null ? "" : alg.trim().toUpperCase(Locale.ROOT);
    }

    private static PublicKey loadPublicKey(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("oidc.public-key-pem 不是合法的 X.509 RSA 公钥");
        }
    }
}
