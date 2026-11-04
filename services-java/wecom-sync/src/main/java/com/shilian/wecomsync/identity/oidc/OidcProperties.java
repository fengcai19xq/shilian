package com.shilian.wecomsync.identity.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OIDC 统一登录参数（WK17）。凭据与密钥只从环境变量读取，仓库内不出现真实值。
 *
 * enabled=false 时 /identity/me 退化为「信任内网网关注入的 X-User-Id」的过渡模式，
 * 仅适用于网关已在外部完成鉴权的部署；接上 IdP 后应打开本开关，让身份来自签名校验过的 id_token。
 */
@ConfigurationProperties(prefix = "oidc")
public record OidcProperties(
        @DefaultValue("false") boolean enabled,
        /** IdP 的 iss，必须与 token 完全一致。 */
        String issuer,
        String clientId,
        /** 期望的签名算法：HS256（共享密钥，内网自签）或 RS256（IdP 公钥）。 */
        @DefaultValue("HS256") String algorithm,
        /** HS256 共享密钥。仅算法为 HS256 时使用。 */
        String hmacSecret,
        /** RS256 公钥，PEM（X.509 SubjectPublicKeyInfo）。仅算法为 RS256 时使用。 */
        String publicKeyPem,
        /** 承载企微 userid 的 claim 名。约定 IdP 在 token 中透出该字段。 */
        @DefaultValue("sub") String userIdClaim,
        /** 允许的时间偏移秒数，容忍多机时钟漂移。 */
        @DefaultValue("60") long clockSkewSeconds) {

    /** 防密钥进日志。 */
    @Override
    public String toString() {
        return "OidcProperties[enabled=" + enabled + ", issuer=" + issuer + ", clientId=" + clientId
                + ", algorithm=" + algorithm + ", userIdClaim=" + userIdClaim
                + ", hmacSecret=" + (hmacSecret == null || hmacSecret.isBlank() ? "<unset>" : "******") + "]";
    }
}
