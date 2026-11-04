package com.shilian.wecomsync.identity.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 把 HTTP 请求头解析成企微 userid（WK17 统一登录）。
 *
 * 两种模式：
 *  - oidc.enabled=true：身份只来自 IdP 签名校验通过的 id_token。前端不再传任何身份，
 *    也不再由构建期注入 VITE_DEV_PRINCIPAL —— 那等于把权限交给客户端。
 *  - oidc.enabled=false：退化为「信任内网网关注入的 X-User-Id」。仅适用于网关已在外部
 *    完成鉴权且网络不可直达本服务的部署；对外暴露时务必打开 OIDC。
 *
 * 这里只做身份定位，权限字段（roles / 密级 / 项目）仍由 IdentityService 按授权表解析。
 */
@Component
public class SessionResolver {

    private final OidcProperties props;
    private final Optional<IdTokenVerifier> verifier;

    public SessionResolver(OidcProperties props, Optional<IdTokenVerifier> verifier) {
        this.props = props;
        this.verifier = verifier;
    }

    /**
     * @param authorization Authorization 头原文（Bearer id_token）
     * @param headerUserId  X-User-Id 头，仅过渡模式使用
     */
    public String resolveWecomUserId(String authorization, String headerUserId) {
        if (props.enabled()) {
            return fromIdToken(authorization);
        }
        String value = headerUserId == null ? "" : headerUserId.strip();
        if (value.isEmpty()) {
            throw new UnauthenticatedException("缺少会话身份");
        }
        return value;
    }

    private String fromIdToken(String authorization) {
        // 开关打开但验签器未装配（缺密钥等）：宁可拒绝，也不退化成信任请求头
        IdTokenVerifier v = verifier.orElseThrow(() -> new UnauthenticatedException("统一登录未正确配置"));
        String token = bearerTokenOf(authorization);
        if (token.isEmpty()) {
            throw new UnauthenticatedException("缺少 id_token");
        }
        try {
            JsonNode claims = v.verify(token);
            return v.userIdOf(claims).orElseThrow(() -> new UnauthenticatedException("id_token 缺少身份声明"));
        } catch (IdTokenVerifier.InvalidTokenException e) {
            // 只回笼统原因：验签细节可能暗示密钥配置，也不入日志
            throw new UnauthenticatedException("会话凭证无效");
        }
    }

    /** 大小写与前缀空白都要容错；非 Bearer 一律视为无效。 */
    static String bearerTokenOf(String authorization) {
        String value = Optional.ofNullable(authorization).orElse("").strip();
        if (value.length() < 8 || !value.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            return "";
        }
        return value.substring(7).strip();
    }
}
