package com.shilian.ingest.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingest")
public record IngestProperties(
        @DefaultValue Chunk chunk,
        @DefaultValue("true") boolean async,
        @DefaultValue("ragflow") String sink,
        @DefaultValue Ragflow ragflow,
        @DefaultValue WeKnora weknora) {

    public boolean weknoraSink() {
        return "weknora".equalsIgnoreCase(sink == null ? "" : sink.strip());
    }

    public record Chunk(@DefaultValue("800") int size, @DefaultValue("100") int overlap) {}

    /** WeKnora 接入配置（WK10）：凭据只从环境变量读取，仓库内不出现真实值。 */
    public record WeKnora(
            String baseUrl,
            String apiKey,
            String tenantId,
            @DefaultValue("600") long parseTimeoutSeconds,
            @DefaultValue("5") long parsePollSeconds,
            /**
             * 建库时要绑定的向量模型名（WeKnora 的 model name）。
             * 缺失会让 WeKnora 建出无法解析的 KB（parse=failed：
             * `failed to get embedding model: model ID cannot be empty`，2026-11-04 现场实测），
             * 因此 sink=weknora 时视为必填。
             */
            String embeddingModel) {

        @Override
        public String toString() {
            return "WeKnora[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "" : "***")
                    + ", tenantId=" + tenantId + ", parseTimeoutSeconds=" + parseTimeoutSeconds
                    + ", embeddingModel=" + embeddingModel + "]";
        }
    }

    /** RAGFlow 接入配置；apiKey 只从环境变量注入，供真实 HTTP 客户端使用（尚未实现）。 */
    public record Ragflow(
            String baseUrl,
            String apiKey,
            @DefaultValue("kb_default") String defaultDataset,
            Map<String, String> deptDatasets) {

        public String datasetFor(String dept) {
            if (deptDatasets != null && deptDatasets.containsKey(dept)) {
                return deptDatasets.get(dept);
            }
            return defaultDataset;
        }

        @Override
        public String toString() {
            return "Ragflow[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "" : "***")
                    + ", defaultDataset=" + defaultDataset + ", deptDatasets=" + deptDatasets + "]";
        }
    }
}
