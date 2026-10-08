package com.shilian.wecomsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 企微连接参数，取值来自环境变量 WECOM_CORP_ID / WECOM_CORP_SECRET / WECOM_BASE_URL。 */
@ConfigurationProperties(prefix = "wecom")
public record WecomProperties(String corpId, String corpSecret, String baseUrl) {

    public boolean configured() {
        return corpId != null && !corpId.isBlank() && corpSecret != null && !corpSecret.isBlank();
    }

    /** 防止密钥进入日志。 */
    @Override
    public String toString() {
        String secret = corpSecret == null || corpSecret.isBlank() ? "<unset>" : "******";
        return "WecomProperties[corpId=" + corpId + ", corpSecret=" + secret + ", baseUrl=" + baseUrl + "]";
    }
}
