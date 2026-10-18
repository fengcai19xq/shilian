package com.shilian.ingest.ragflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 用 JDK 内置 HttpServer 模拟 RAGFlow，校验请求路径、鉴权头、元数据写入与错误映射。 */
class HttpRagflowClientTest {

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpRagflowClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI();
            calls.add(key);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(ex.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            String resp;
            if (key.startsWith("GET /api/v1/datasets?name=kb_exists")) {
                resp = "{\"code\":0,\"data\":[{\"id\":\"ds-1\",\"name\":\"kb_exists\"}]}";
            } else if (key.startsWith("GET /api/v1/datasets?name=")) {
                resp = "{\"code\":0,\"data\":[]}";
            } else if (key.equals("POST /api/v1/datasets")) {
                resp = "{\"code\":0,\"data\":{\"id\":\"ds-new\"}}";
            } else if (key.equals("POST /api/v1/datasets/ds-1/documents")) {
                resp = "{\"code\":0,\"data\":[{\"id\":\"doc-1\"}]}";
            } else if (key.equals("POST /api/v1/datasets/ds-1/documents/doc-1/chunks")) {
                resp = "{\"code\":0,\"data\":{\"chunk\":{\"id\":\"c\"}}}";
            } else if (key.startsWith("POST /api/v1/datasets/bad/")) {
                resp = "{\"code\":102,\"message\":\"secret file name.pdf\"}";
            } else {
                resp = "{\"code\":0,\"data\":true}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = new HttpRagflowClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "test-key");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createDatasetIsIdempotent() {
        assertThat(client.createDataset("kb_exists")).isEqualTo("ds-1");
        assertThat(client.createDataset("kb_new")).isEqualTo("ds-new");
        assertThat(calls).containsExactly(
                "GET /api/v1/datasets?name=kb_exists",
                "GET /api/v1/datasets?name=kb_new",
                "POST /api/v1/datasets");
    }

    @Test
    void uploadWritesMetaFieldsThenChunks() throws IOException {
        String id = client.uploadDocument("ds-1", "a.pdf", "x".getBytes(StandardCharsets.UTF_8),
                Map.of("dept", "融资部", "sensitivity", "L2"));
        assertThat(id).isEqualTo("doc-1");
        assertThat(calls.get(0)).isEqualTo("POST /api/v1/datasets/ds-1/documents");
        assertThat(bodies.get(0)).contains("name=\"file\"; filename=\"a.pdf\"");
        assertThat(calls.get(1)).isEqualTo("POST /api/v1/datasets/ds-1/metadata/update");
        JsonNode meta = mapper.readTree(bodies.get(1));
        assertThat(meta.path("selector").path("document_ids").get(0).asText()).isEqualTo("doc-1");
        java.util.Map<String, String> updates = new java.util.HashMap<>();
        meta.path("updates").forEach(u -> updates.put(u.path("key").asText(), u.path("value").asText()));
        assertThat(updates).containsEntry("dept", "融资部").containsEntry("sensitivity", "L2");

        client.upsertChunks("ds-1", "doc-1", List.of(new RagflowChunk("c1", "正文", 3, Map.of())));
        JsonNode chunk = mapper.readTree(bodies.get(2));
        assertThat(chunk.path("content").asText()).isEqualTo("正文");
        assertThat(chunk.path("important_keywords").toString()).contains("chunk:c1", "page:3");
    }

    @Test
    void nonZeroCodeRaisesWithoutLeakingMessage() {
        assertThatThrownBy(() -> client.uploadDocument("bad", "secret.pdf", new byte[1], Map.of()))
                .isInstanceOf(RagflowException.class)
                .hasMessage("RAGFlow code 102");
    }

    @Test
    void blankConfigRejected() {
        assertThatThrownBy(() -> new HttpRagflowClient("", "k")).isInstanceOf(IllegalArgumentException.class);
    }
}
