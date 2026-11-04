package com.shilian.wecomsync.weknora;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WeKnora 投影配置（WK14）。凭据只从环境变量读取，仓库内不出现真实值。
 *
 * enabled 默认 false：未显式开启时投影只出计划、不触碰后端，
 * 保证接入 WeKnora 前现有企微同步行为完全不变。
 */
@ConfigurationProperties(prefix = "weknora")
public record ProjectionProperties(
        @DefaultValue("false") boolean enabled,
        String baseUrl,
        String apiKey,
        String tenantId,
        @DefaultValue("default") String tenant,
        @DefaultValue("identity.shilian.local") String emailDomain,
        @DefaultValue({ "financing_manager", "it_admin" }) List<String> adminRoles,
        @DefaultValue({ "financing_staff" }) List<String> contributorRoles,
        /**
         * 预置 KB 要绑定的向量模型名，必须与 ingest.weknora.embedding-model 一致。
         * 两边不一致会各自建出不同绑定的库；投影建出的库若没绑模型，
         * 后续 ingest 会拒绝复用（未绑定的 KB 无法补绑，文档必然解析失败）。
         */
        String embeddingModel) {

    /** 只有开关打开且地址与密钥齐备才真正下发。 */
    public boolean active() {
        return enabled && baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    /** 防密钥进日志。 */
    @Override
    public String toString() {
        return "ProjectionProperties[enabled=" + enabled + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "******")
                + ", tenantId=" + tenantId + ", tenant=" + tenant
                + ", embeddingModel=" + embeddingModel + "]";
    }
}
