package com.shilian.wecomsync.identity.oidc;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 测试用迷你 JWT 构造器：既能模拟 IdP 正常签发，也能模拟攻击者伪造
 * （alg=none、拿公钥当 HMAC 密钥等）。仅供 wecom-sync 模块测试使用。
 */
public final class TestJwt {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private TestJwt() {
    }

    public static String hs256(String secret, String payload) throws Exception {
        String input = header("HS256") + "." + enc(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + URL.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    public static String rs256(PrivateKey key, String payload) throws Exception {
        return rs256(key, "RS256", payload);
    }

    /** 按指定 alg 文本写 header 并正确签名，用于测大小写变体等。 */
    public static String rs256(PrivateKey key, String alg, String payload) throws Exception {
        String input = header(alg) + "." + enc(payload);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + URL.encodeToString(sig.sign());
    }

    /** 第三段留非空占位，否则会被判成段数不足，测不到「拒绝 none」这条路径。 */
    public static String unsigned(String payload) {
        return header("none") + "." + enc(payload) + ".not-a-real-signature";
    }

    public static String toPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    public static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** 用密钥当 HMAC 密钥签名 —— 算法混淆攻击的标准手法。 */
    public static String hmacWith(PublicKey key, String payload) throws Exception {
        return hs256(toPem(key), payload);
    }

    private static String header(String alg) {
        return enc("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}");
    }

    private static String enc(String s) {
        return URL.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
