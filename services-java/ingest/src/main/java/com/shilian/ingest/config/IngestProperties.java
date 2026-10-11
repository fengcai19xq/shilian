package com.shilian.ingest.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingest")
public record IngestProperties(
        @DefaultValue Chunk chunk,
        @DefaultValue("true") boolean async,
        @DefaultValue Ragflow ragflow) {

    public record Chunk(@DefaultValue("800") int size, @DefaultValue("100") int overlap) {}

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
