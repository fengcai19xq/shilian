package com.shilian.wecomsync.weknora;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用 JDK 内置 HttpServer 模拟 WeKnora，覆盖投影侧建库的真实 HTTP 行为（WK14）。
 *
 * 重点是 Fake 客户端测不到的部分：建库前解析向量模型、把 id 绑进请求体、
 * 复用未绑定模型的老库时提前报错——这几条决定了后续入库链路能否跑通。
 */
class HttpWeknoraIdentityClientTest {

    private static final String MODEL_NAME = "BAAI/bge-m3";
    private static final String MODEL_ID = "emb-1";
    private static final String KB_NAME = "sl-acme-L2-878d8d4490e8";
    private static final String EXISTING_KB = "已存在的库";

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 已存在的 KB 是否绑定了向量模型，由用例改写。 */
    private volatile boolean existingKbBound = true;
    /** Embedding 模型的 status，用于验证只认 active。 */
    private volatile String embeddingStatus = "active";
    /** 列表里是否含目标名称的 Embedding 模型。 */
    private volatile boolean embeddingPresent = true;
    /** 是否让一个同名但类型不对的模型出现在列表里（同名不同类不能误绑）。 */
    private volatile boolean qaSharesModelName = false;

    private HttpWeknoraIdentityClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI();
            calls.add(key);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(ex.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("test-key");
            assertThat(ex.getRequestHeaders().getFirst("X-Tenant-ID")).isEqualTo("10001");

            List<String> models = new ArrayList<>();
            if (embeddingPresent) {
                models.add("{\"id\":\"" + MODEL_ID + "\",\"name\":\"" + MODEL_NAME
                        + "\",\"type\":\"Embedding\",\"status\":\"" + embeddingStatus + "\"}");
            }
            if (qaSharesModelName) {
                models.add("{\"id\":\"qa-1\",\"name\":\"" + MODEL_NAME
                        + "\",\"type\":\"KnowledgeQA\",\"status\":\"active\"}");
            }
            if (!embeddingPresent && !qaSharesModelName) {
                models.add("{\"id\":\"qa-2\",\"name\":\"qwen-plus\",\"type\":\"KnowledgeQA\",\"status\":\"active\"}");
            }

            String resp;
            if (key.equals("GET /api/v1/models")) {
                resp = "{\"success\":true,\"data\":[" + String.join(",", models) + "]}";
            } else if (key.equals("GET /api/v1/knowledge-bases")) {
                resp = "{\"success\":true,\"data\":[{\"id\":\"kb-old\",\"name\":\"" + EXISTING_KB
                        + "\",\"embedding_model_id\":" + (existingKbBound ? "\"" + MODEL_ID + "\"" : "\"\"") + "}]}";
            } else if (key.equals("POST /api/v1/knowledge-bases")) {
                resp = "{\"success\":true,\"data\":{\"id\":\"kb-new\",\"name\":\"" + KB_NAME + "\"}}";
            } else {
                resp = "{\"success\":true,\"data\":true}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = newClient();
    }

    private HttpWeknoraIdentityClient newClient() {
        return new HttpWeknoraIdentityClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "test-key", "10001", MODEL_NAME);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsKbWithResolvedEmbeddingModelId() throws IOException {
        String id = client.createKb(KB_NAME);

        assertThat(id).isEqualTo("kb-new");
        assertThat(calls).containsExactly(
                "GET /api/v1/models", "GET /api/v1/knowledge-bases", "POST /api/v1/knowledge-bases");
        JsonNode body = mapper.readTree(bodies.get(2));
        assertThat(body.path("name").asText()).isEqualTo(KB_NAME);
        assertThat(body.path("embedding_model_id").asText()).isEqualTo(MODEL_ID);
    }

    /** 模型名→id 只解析一次，多次预置 KB 不该反复拉模型列表。 */
    @Test
    void resolvesModelOnceAcrossKbs() {
        client.createKb("库一");
        client.createKb("库二");

        assertThat(calls.stream().filter(c -> c.equals("GET /api/v1/models")).count()).isEqualTo(1);
    }

    /** 复用已存在且已绑定的库：返回既有 id，不再新建。 */
    @Test
    void reusesExistingBoundKb() {
        assertThat(client.createKb(EXISTING_KB)).isEqualTo("kb-old");
        assertThat(calls).containsExactly("GET /api/v1/models", "GET /api/v1/knowledge-bases");
    }

    /**
     * 早期版本建出的库可能没绑向量模型，而 PUT 补绑实测不生效、文档必然解析失败。
     * 复用时提前报错，别让流水线带着坏库跑下去。
     */
    @Test
    void refusesToReuseUnboundKb() {
        existingKbBound = false;

        assertThatThrownBy(() -> client.createKb(EXISTING_KB))
                .isInstanceOf(WeknoraApiException.class)
                .hasMessageContaining("未绑定向量模型")
                .hasMessageContaining("删除");
        assertThat(calls).doesNotContain("POST /api/v1/knowledge-bases");
    }

    @Test
    void unknownModelNameFailsBeforeCreatingKb() {
        embeddingPresent = false;

        assertThatThrownBy(() -> client.createKb(KB_NAME))
                .isInstanceOf(WeknoraApiException.class)
                .hasMessageContaining("找不到可用的向量模型")
                .hasMessageContaining(MODEL_NAME);
        assertThat(calls).doesNotContain("POST /api/v1/knowledge-bases");
    }

    @Test
    void inactiveEmbeddingModelIsNotUsed() {
        embeddingStatus = "disabled";

        assertThatThrownBy(() -> client.createKb(KB_NAME))
                .isInstanceOf(WeknoraApiException.class)
                .hasMessageContaining("找不到可用的向量模型");
    }

    /** 同名但类型是 KnowledgeQA 的模型不能被误当成向量模型绑上。 */
    @Test
    void ignoresSameNameNonEmbeddingModel() {
        embeddingPresent = false;
        qaSharesModelName = true;

        assertThatThrownBy(() -> client.createKb(KB_NAME))
                .isInstanceOf(WeknoraApiException.class)
                .hasMessageContaining("找不到可用的向量模型");
        assertThat(calls).doesNotContain("POST /api/v1/knowledge-bases");
    }

    /** 凭据或模型名缺失都在构造期拦住，不留到运行期建出坏库。 */
    @Test
    void blankConfigRejectedAtConstruction() {
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("", "k", "1", MODEL_NAME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("http://x", null, "1", MODEL_NAME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("http://x", "k", "1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("http://x", "k", "1", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
    }
}
