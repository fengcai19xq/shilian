package com.shilian.wecomsync.identity.oidc;

/** 未登录或令牌无效。统一映射为 401，不回显任何令牌细节。 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String reason) {
        super(reason);
    }
}
