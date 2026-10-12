package com.shilian.ingest.ragflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAGFlow HTTP API 客户端（/api/v1/datasets、/documents、/chunks）。
 * 元数据通过文档级 meta_fields 写入，供 retrieval-proxy 以 metadata_condition 前置过滤。
 * 异常信息只含状态码/RAGFlow code，不含文件名与内容。
 */
public class HttpRagflowClient implements RagflowClient {

    private static final String API = "/api/v1";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;

    public HttpRagflowClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public HttpRagflowClient(String baseUrl, String apiKey, HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ingest.ragflow.base-url / api-key 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public String createDataset(String name) {
        JsonNode existing = send(get(API + "/datasets?name=" + enc(name)));
        if (existing.isArray() && !existing.isEmpty()) {
            return existing.get(0).path("id").asText();
        }
        ObjectNode body = mapper.createObjectNode().put("name", name);
        return send(json("POST", API + "/datasets", body)).path("id").asText();
    }

    @Override
    public String uploadDocument(String datasetId, String fileName, byte[] content, Map<String, String> metadata) {
        String boundary = "----shilian" + UUID.randomUUID();
        byte[] multipart = multipart(boundary, fileName, content);
        HttpRequest req = base(API + "/datasets/" + datasetId + "/documents")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build();
        JsonNode docs = send(req);
        if (!docs.isArray() || docs.isEmpty()) {
            throw new RagflowException("RAGFlow 上传未返回文档 id");
        }
        String documentId = docs.get(0).path("id").asText();

        ObjectNode meta = mapper.createObjectNode();
        metadata.forEach(meta::put);
        ObjectNode update = mapper.createObjectNode();
        update.putArray("document_ids").add(documentId);
        update.set("updates", meta);
        send(json("POST", API + "/datasets/" + datasetId + "/metadata/update", update));
        return documentId;
    }

    @Override
    public void upsertChunks(String datasetId, String documentId, List<RagflowChunk> chunks) {
        String path = API + "/datasets/" + datasetId + "/documents/" + documentId + "/chunks";
        for (RagflowChunk chunk : chunks) {
            ObjectNode body = mapper.createObjectNode().put("content", chunk.content());
            ArrayNode kw = body.putArray("important_keywords");
            kw.add("chunk:" + chunk.chunkId());
            if (chunk.page() > 0) {
                kw.add("page:" + chunk.page());
            }
            send(json("POST", path, body));
        }
    }

    @Override
    public void deleteDocument(String datasetId, String documentId) {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("ids").add(documentId);
        try {
            send(json("DELETE", API + "/datasets/" + datasetId + "/documents", body));
        } catch (RagflowException e) {
            if (!e.getMessage().contains("102")) {
                throw e;
            }
        }
    }

    private HttpRequest get(String path) {
        return base(path).GET().build();
    }

    private HttpRequest json(String method, String path, JsonNode body) {
        return base(path)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey);
    }

    /** 返回 data 节点；HTTP ≥400 或 code≠0 抛 RagflowException。 */
    private JsonNode send(HttpRequest req) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RagflowException("RAGFlow 请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagflowException("RAGFlow 请求被中断", e);
        }
        if (resp.statusCode() >= 400) {
            throw new RagflowException("RAGFlow HTTP " + resp.statusCode());
        }
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new RagflowException("RAGFlow 响应不是 JSON", e);
        }
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new RagflowException("RAGFlow code " + code);
        }
        return root.path("data");
    }

    private static byte[] multipart(String boundary, String fileName, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName.replace("\"", "_") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
