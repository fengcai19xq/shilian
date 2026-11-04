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

/**
 * 用 JDK 内置 HttpServer 模拟 WeKnora，校验鉴权头（X-API-Key，非 Bearer）、
 * KB 幂等创建、project→tag 下发、服务端解析轮询与错误映射不泄露原文。
 */
class HttpWeKnoraClientTest {

    private static final String KB = "sl-acme-L2-878d8d4490e8";
    private static final String MODEL_NAME = "BAAI/bge-m3";
    private static final String MODEL_ID = "emb-1";

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    /** 由用例改写：knowledge 列表返回的状态。 */
    private volatile String parseStatus = "completed";
    private volatile String serverErrorMessage = "";
    /** 由用例改写：向量模型的 status，用于验证只认 active。 */
    private volatile String embeddingStatus = "active";
    /** 由用例改写：已存在的 KB 是否绑定了向量模型。 */
    private volatile boolean existingKbBound = true;

    private HttpWeKnoraClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI();
            calls.add(key);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(ex.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("test-key");
            assertThat(ex.getRequestHeaders().getFirst("X-Tenant-ID")).isEqualTo("9001");

            String resp;
            int status = 200;
            if (key.equals("GET /api/v1/models")) {
                resp = "{\"success\":true,\"data\":[{\"id\":\"" + MODEL_ID + "\",\"name\":\"" + MODEL_NAME
                        + "\",\"type\":\"Embedding\",\"status\":\"" + embeddingStatus + "\"},"
                        + "{\"id\":\"qa-1\",\"name\":\"qwen-plus\",\"type\":\"KnowledgeQA\",\"status\":\"active\"}]}";
            } else if (key.equals("GET /api/v1/knowledge-bases")) {
                resp = "{\"success\":true,\"data\":[{\"id\":\"kb-other\",\"name\":\"别的库\",\"embedding_model_id\":\""
                        + (existingKbBound ? MODEL_ID : "") + "\"}]}";
            } else if (key.equals("POST /api/v1/knowledge-bases")) {
                resp = "{\"success\":true,\"data\":{\"id\":\"kb-1\",\"name\":\"" + KB + "\"}}";
            } else if (key.equals("GET /api/v1/knowledge-bases/kb-1/tags")) {
                resp = "{\"success\":true,\"data\":{\"items\":[]}}";
            } else if (key.equals("POST /api/v1/knowledge-bases/kb-1/tags")) {
                resp = "{\"success\":true,\"data\":{\"id\":\"tag-1\"}}";
            } else if (key.equals("POST /api/v1/knowledge-bases/kb-1/knowledge/file")) {
                resp = "{\"success\":true,\"data\":{\"id\":\"k-1\"}}";
            } else if (key.startsWith("GET /api/v1/knowledge-bases/kb-1/knowledge?")) {
                resp = "{\"success\":true,\"data\":[{\"id\":\"k-1\",\"parse_status\":\"" + parseStatus
                        + "\",\"page_count\":7,\"chunk_count\":42,\"error_message\":\"" + serverErrorMessage + "\"}]}";
            } else if (key.equals("DELETE /api/v1/knowledge-bases/kb-1/knowledge/gone")) {
                status = 404;
                resp = "{\"success\":false,\"error\":{\"code\":1003,\"message\":\"secret.pdf not found\"}}";
            } else if (key.equals("DELETE /api/v1/knowledge-bases/kb-1/knowledge/k-1")) {
                resp = "{\"success\":true,\"data\":true}";
            } else if (key.contains("/knowledge-bases/bad/")) {
                status = 500;
                resp = "{\"success\":false,\"error\":{\"code\":1000,\"message\":\"含机密文件名 报表.xlsx 内容片段\"}}";
            } else {
                resp = "{\"success\":true,\"data\":true}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = newClient(2, 1);
    }

    private HttpWeKnoraClient newClient(long timeoutSec, long pollSec) {
        return newClient(timeoutSec, pollSec, MODEL_NAME);
    }

    private HttpWeKnoraClient newClient(long timeoutSec, long pollSec, String embeddingModel) {
        return new HttpWeKnoraClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                "test-key", "9001", timeoutSec, pollSec, embeddingModel);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void declaresServerSideParsing() {
        assertThat(client.serverSideParsing()).isTrue();
    }

    @Test
    void localChunkPushIsRejected() {
        assertThatThrownBy(() -> client.upsertChunks("kb-1", "k-1", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void createDatasetIsIdempotent() {
        assertThat(client.createDataset("不存在的库")).isEqualTo("kb-1");
        assertThat(calls).containsExactly(
                "GET /api/v1/models", "GET /api/v1/knowledge-bases", "POST /api/v1/knowledge-bases");

        calls.clear();
        // 第二次命中已存在名称：直接复用 id，不再创建
        HttpWeKnoraClient c2 = newClient(2, 1);
        assertThat(c2.createDataset("别的库")).isEqualTo("kb-other");
        assertThat(calls).containsExactly("GET /api/v1/models", "GET /api/v1/knowledge-bases");
    }

    /** 建出的 KB 必须绑定向量模型：不绑定的 KB 文档解析必然失败（现场实测 parse=failed）。 */
    @Test
    void createDatasetBindsResolvedEmbeddingModelId() throws IOException {
        client.createDataset(KB);
        JsonNode body = mapper.readTree(bodies.get(bodies.size() - 1));
        assertThat(body.path("embedding_model_id").asText()).isEqualTo(MODEL_ID);
        assertThat(body.path("name").asText()).isEqualTo(KB);
    }

    /** 模型名→id 只解析一次，避免每次建库都拉一遍模型列表。 */
    @Test
    void embeddingModelResolvedOnceAndCached() {
        client.createDataset("库一");
        client.createDataset("库二");
        long modelCalls = calls.stream().filter(c -> c.equals("GET /api/v1/models")).count();
        assertThat(modelCalls).isEqualTo(1);
    }

    /**
     * 早期版本或投影侧建出的 KB 可能没绑模型且无法补绑；复用时必须提前拦住，
     * 不能让文档一路走到解析阶段才报 `model ID cannot be empty`。
     */
    @Test
    void reusingUnboundKbFailsFastWithActionableMessage() {
        existingKbBound = false;
        assertThatThrownBy(() -> client.createDataset("别的库"))
                .isInstanceOf(RagflowException.class)
                .hasMessageContaining("未绑定向量模型")
                .hasMessageContaining("删除");
        // 拦在复用判断处，不应再发建库请求
        assertThat(calls).doesNotContain("POST /api/v1/knowledge-bases");
    }

    @Test
    void embeddingModelMustBeConfiguredAtConstruction() {
        assertThatThrownBy(() -> new HttpWeKnoraClient("http://x", "k", "1", 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
        assertThatThrownBy(() -> new HttpWeKnoraClient("http://x", "k", "1", 0, 0, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
    }

    /** 名称对不上不能默默建成空绑定 KB，要当场报错。 */
    @Test
    void unknownEmbeddingModelNameFailsFast() {
        HttpWeKnoraClient c = newClient(2, 1, "BAAI/not-installed");
        assertThatThrownBy(() -> c.createDataset(KB))
                .isInstanceOf(RagflowException.class)
                .hasMessageContaining("找不到可用的向量模型")
                .hasMessageContaining("BAAI/not-installed");
        // 失败前不应已经发出建库请求
        assertThat(calls).doesNotContain("POST /api/v1/knowledge-bases");
    }

    /** 非 active 的模型不可用（上游可能留着禁用状态的记录）。 */
    @Test
    void inactiveEmbeddingModelIsRejected() {
        embeddingStatus = "disabled";
        assertThatThrownBy(() -> client.createDataset(KB))
                .isInstanceOf(RagflowException.class)
                .hasMessageContaining("找不到可用的向量模型");
    }

    @Test
    void uploadCreatesProjectTagAndSendsItWithFile() throws IOException {
        String id = client.uploadDocument("kb-1", "a.pdf", "x".getBytes(StandardCharsets.UTF_8),
                Map.of("dept", "融资部", "sensitivity", "L2", "project", "PRJ-DEMO-A"));
        assertThat(id).isEqualTo("k-1");

        // 先查标签、再建标签、最后带 tag_ids 上传
        assertThat(calls).containsExactly(
                "GET /api/v1/knowledge-bases/kb-1/tags",
                "POST /api/v1/knowledge-bases/kb-1/tags",
                "POST /api/v1/knowledge-bases/kb-1/knowledge/file");
        JsonNode tagBody = mapper.readTree(bodies.get(1));
        assertThat(tagBody.path("name").asText()).isEqualTo(KbEncoding.projectTag("PRJ-DEMO-A"));

        String multipart = bodies.get(2);
        assertThat(multipart).contains("name=\"tag_ids\"").contains("tag-1");
        assertThat(multipart).contains("name=\"file\"; filename=\"a.pdf\"");
    }

    @Test
    void uploadWithoutProjectUsesSentinelTag() throws IOException {
        client.uploadDocument("kb-1", "a.pdf", new byte[1],
                Map.of("dept", "融资部", "sensitivity", "L2"));
        JsonNode tagBody = mapper.readTree(bodies.get(1));
        assertThat(tagBody.path("name").asText()).isEqualTo("project::__none__");
    }

    @Test
    void waitForIndexedReturnsStatsWhenCompleted() {
        RagflowClient.ParseStats stats = client.waitForIndexed("kb-1", "k-1");
        assertThat(stats.pageCount()).isEqualTo(7);
        assertThat(stats.chunkCount()).isEqualTo(42);
    }

    @Test
    void waitForIndexedMapsFailureToFixedReasonWithoutLeakingServerText() {
        parseStatus = "failed";
        serverErrorMessage = "wrong password for 报表.xlsx";
        assertThatThrownBy(() -> client.waitForIndexed("kb-1", "k-1"))
                .isInstanceOf(ServerSideParseException.class)
                .extracting(e -> ((ServerSideParseException) e).getUserReason())
                .isEqualTo("文档已加密，请解密后重新上传");
    }

    @Test
    void waitForIndexedTimesOutWithLastStatus() {
        parseStatus = "pending";
        HttpWeKnoraClient slow = newClient(1, 1);
        assertThatThrownBy(() -> slow.waitForIndexed("kb-1", "k-1"))
                .isInstanceOf(RagflowException.class)
                .hasMessageContaining("超时")
                .hasMessageContaining("pending");
    }

    @Test
    void deleteToleratesMissingDocumentButRaisesOtherErrors() {
        client.deleteDocument("kb-1", "gone");
        assertThatThrownBy(() -> client.deleteDocument("bad", "k-9"))
                .isInstanceOf(RagflowException.class)
                .hasMessage("WeKnora HTTP 500");
    }

    @Test
    void httpErrorDoesNotLeakServerMessage() {
        assertThatThrownBy(() -> client.uploadDocument("bad", "机密.pdf", new byte[1], Map.of()))
                .isInstanceOf(RagflowException.class)
                .hasMessage("WeKnora HTTP 500")
                .hasMessageNotContaining("机密");
    }

    @Test
    void blankConfigRejected() {
        assertThatThrownBy(() -> new HttpWeKnoraClient("", "k", "1", 0, 0, "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWeKnoraClient("http://x", "", "1", 0, 0, "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonOfCategorisesKnownFailures() {
        assertThat(HttpWeKnoraClient.reasonOf("FILE IS ENCRYPTED")).isEqualTo("文档已加密，请解密后重新上传");
        assertThat(HttpWeKnoraClient.reasonOf("corrupted zip"))
                .isEqualTo("文件损坏或格式不受支持，请检查后重新上传");
        assertThat(HttpWeKnoraClient.reasonOf("context deadline Timeout")).isEqualTo("服务端解析超时，请稍后重试");
        assertThat(HttpWeKnoraClient.reasonOf("")).isEqualTo("服务端解析失败，请检查文档内容或稍后重试");
        assertThat(HttpWeKnoraClient.reasonOf(null)).isEqualTo("服务端解析失败，请检查文档内容或稍后重试");
    }

    @Test
    void itemsHandlesAllThreeDataShapes() {
        // 数组、{items:[]} 已在上面用例覆盖；对象形态（创建返回）由 createDataset/upload 用例覆盖
        assertThat(client.createDataset(KB)).isEqualTo("kb-1");
    }
}
