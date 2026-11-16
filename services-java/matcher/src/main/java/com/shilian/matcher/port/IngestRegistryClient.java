package com.shilian.matcher.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.Scope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 调用 ingest 服务获取文档元数据（WK18 登记富化）。
 *
 * <p>用于按 doc_id 查询文档的 copies/stamped/std_type 等字段，富化候选文档。
 * 富化失败时返回空 Optional，由调用方决定如何处理（通常判 missing）。
 */
public class IngestRegistryClient {

    private final String ingestBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public IngestRegistryClient(String ingestBaseUrl) {
        this(ingestBaseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public IngestRegistryClient(String ingestBaseUrl, HttpClient httpClient) {
        this.ingestBaseUrl = ingestBaseUrl;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 按 doc_id 获取文档元数据，返回富化后的 CandidateDoc。
     *
     * @param docId 文档 ID
     * @return 富化后的 CandidateDoc，查询失败或文档不存在时返回 empty
     */
    public Optional<CandidateDoc> enrich(String docId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ingestBaseUrl + "/ingest/documents/" + docId))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return Optional.empty();
            }

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode doc = objectMapper.readTree(response.body());
            return Optional.of(mapToCandidateDoc(docId, doc));

        } catch (IOException | InterruptedException e) {
            return Optional.empty();
        }
    }

    private static String textOf(JsonNode doc, String snake, String camel) {
        for (String key : new String[] {snake, camel}) {
            if (doc.has(key) && !doc.get(key).isNull()) {
                return doc.get(key).asText();
            }
        }
        return null;
    }

    private CandidateDoc mapToCandidateDoc(String docId, JsonNode doc) {
        Integer copies = doc.has("copies") && !doc.get("copies").isNull()
                ? doc.get("copies").asInt() : null;
        Boolean stamped = doc.has("stamped") && !doc.get("stamped").isNull()
                ? doc.get("stamped").asBoolean() : null;
        // ingest 的 JSON 是 snake_case；兼容旧 camelCase 只用于过渡
        String stdType = textOf(doc, "std_type", "stdType");

        // period 需要是 List<String>
        List<String> period = doc.has("period") && !doc.get("period").isNull()
                ? List.of(doc.get("period").asText())
                : List.of();

        // scope 需要是 Scope 枚举
        Scope scope = null;
        if (doc.has("scope") && !doc.get("scope").isNull()) {
            String scopeStr = doc.get("scope").asText();
            if ("consolidated".equals(scopeStr) || "合并".equals(scopeStr)) {
                scope = Scope.CONSOLIDATED;
            } else if ("standalone".equals(scopeStr) || "单体".equals(scopeStr)) {
                scope = Scope.STANDALONE;
            }
        }

        return new CandidateDoc(
                docId,
                doc.has("title") ? doc.get("title").asText() : "",
                // 原始文件 URI 由 ingest 登记提供，packager 按它读取
                textOf(doc, "storage_uri", "storageUri") == null ? "" : textOf(doc, "storage_uri", "storageUri"),
                stdType,
                period,
                scope,
                copies,
                stamped,
                List.of(), // pages
                0.0, // recallScore
                0.0  // formScore
        );
    }
}
