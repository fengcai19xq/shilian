package com.shilian.wecomsync.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端点鉴权（/identity/sync、/identity/principal/{id}、/grants、/weknora/project、/audit-events）。
 *
 * <p>这些端点能改授权、能触发全量同步，不能只靠“内网不可达”保护。
 * 默认 {@code identity.require-admin-token=true}：未配置令牌时直接 403，宁可管理面不可用，
 * 也不让授权写操作裸奔。测试上下文显式置 false，仅用于既有用例。
 *
 * <p>{@code /identity/me} 是会话身份入口，不走管理员令牌，由 OIDC / 过渡头校验负责。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final String adminToken;
    private final boolean required;

    public AdminAuthInterceptor(
            @Value("${identity.admin-token:}") String adminToken,
            @Value("${identity.require-admin-token:true}") boolean required) {
        this.adminToken = adminToken == null ? "" : adminToken;
        this.required = required;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!required) {
            return true;
        }
        if (adminToken.isBlank()) {
            write(response, HttpServletResponse.SC_FORBIDDEN, "admin_auth_not_configured",
                    "管理接口未配置管理员令牌");
            return false;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "admin_auth_failed",
                "管理员令牌无效");
        return false;
    }

    private static void write(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
