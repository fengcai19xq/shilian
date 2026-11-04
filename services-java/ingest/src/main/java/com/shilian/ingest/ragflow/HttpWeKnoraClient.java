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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WeKnora 推送通道（WK10 / contracts/weknora-integration.md）。
 *
 * 与 RAGFlow 通道的三点差异：
 *  1. serverSideParsing=true —— 解析与切块交给 WeKnora 的 docreader，本地不再解析、不推块；
 *  2. 权限维度落点不同 —— dept/sensitivity 由调用方编码进 KB 名（KbEncoding），
 *     project 以 tag 形式随上传下发；period/scope 不进引擎（tag 槽专属 project，
 *     混用会让 IN 的 OR 语义污染权限判定），成册统计一律从 shilian 登记记录取。
 *  3. 鉴权用 X-API-Key（空间级 Key 的 ingest 能力位），不是 Bearer。
 *
 * 异常信息只含状态码与 WeKnora code，不回显文件名与服务端原文。
 */
public class HttpWeKnoraClient implements RagflowClient {

    private static final String API = "/api/v1";
    private static final Pattern HEX4 = Pattern.compile("^p(?:[0-9a-f]{4})+$");

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String tenantId;
    private final long parseTimeoutSeconds;
    private final long parsePollSeconds;
    private final String embeddingModel;
    /** 模型名→id 只解析一次：KB 可能建很多次，模型列表基本不变。 */
    private volatile String embeddingModelId;

    public HttpWeKnoraClient(String baseUrl, String apiKey, String tenantId,
            long parseTimeoutSeconds, long parsePollSeconds, String embeddingModel) {
        this(baseUrl, apiKey, tenantId, parseTimeoutSeconds, parsePollSeconds, embeddingModel,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public HttpWeKnoraClient(String baseUrl, String apiKey, String tenantId,
            long parseTimeoutSeconds, long parsePollSeconds, String embeddingModel, HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ingest.weknora.base-url / api-key 不能为空");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            // 不绑向量模型建出来的 KB 必然解析失败，且要到解析阶段才报错——这里提前拦住
            throw new IllegalArgumentException("ingest.weknora.embedding-model 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.tenantId = tenantId == null ? "" : tenantId.strip();
        this.parseTimeoutSeconds = parseTimeoutSeconds <= 0 ? 600 : parseTimeoutSeconds;
        this.parsePollSeconds = parsePollSeconds <= 0 ? 5 : parsePollSeconds;
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel.strip();
        this.http = http;
    }

    @Override
    public boolean serverSideParsing() {
        return true;
    }

    /**
     * 同名 KB 已存在时返回其 id（幂等）。
     *
     * 建库必须绑定向量模型：WeKnora 不会自己挑，缺了就建成一个解析必然失败的 KB
     * （parse=failed，`failed to get embedding model: model ID cannot be empty`）。
     * 那种 KB 会让文档一路走到解析阶段才报错，所以这里改为建库前就快速失败。
     */
    @Override
    public String createDataset(String name) {
        String modelId = resolveEmbeddingModelId();
        JsonNode list = send(get(API + "/knowledge-bases"));
        for (JsonNode kb : items(list)) {
            if (name.equals(kb.path("name").asText())) {
                requireBoundEmbedding(kb, modelId);
                return kb.path("id").asText();
            }
        }
        ObjectNode body = mapper.createObjectNode().put("name", name);
        body.put("type", "user");
        body.put("description", "shilian 自动创建（密级×部门复合命名，勿手动改名）");
        body.put("embedding_model_id", modelId);
        return requireId(send(json("POST", API + "/knowledge-bases", body)), "KB 创建未返回 id");
    }

    /**
     * 复用已存在的 KB 前，确认它真的绑了向量模型。
     *
     * 早期版本（以及身份投影侧）建出来的 KB 可能没绑定，而这类库**无法事后补绑**：
     * PUT 改不动 embedding_model_id，文档会一路走到解析阶段才报
     * `model ID cannot be empty`，排查成本极高。所以这里直接拦在建库入口，
     * 给出可操作的错误（删除该 KB 让流水线重建）。
     */
    private void requireBoundEmbedding(JsonNode kb, String modelId) {
        String bound = kb.path("embedding_model_id").asText("");
        if (!bound.isBlank()) {
            return;
        }
        throw new RagflowException("已存在的知识库 " + kb.path("name").asText("")
                + " 未绑定向量模型且无法补绑，请在 WeKnora 删除该 KB 后重试（将由本服务按配置重建）");
    }

    /** 模型名→id。只接受 active 的 Embedding 模型；解析不到就抛，绝不建空绑定的 KB。 */
    private String resolveEmbeddingModelId() {
        String cached = embeddingModelId;
        if (cached != null) {
            return cached;
        }
        JsonNode models = send(get(API + "/models"));
        for (JsonNode m : items(models)) {
            if (!"Embedding".equals(m.path("type").asText())) {
                continue;
            }
            if (!"active".equals(m.path("status").asText("active"))) {
                continue;
            }
            if (embeddingModel.equals(m.path("name").asText())) {
                String id = m.path("id").asText("");
                if (id.isBlank()) {
                    throw new RagflowException("WeKnora 向量模型未返回 id");
                }
                embeddingModelId = id;
                return id;
            }
        }
        throw new RagflowException("WeKnora 空间内找不到可用的向量模型: " + embeddingModel);
    }

    /**
     * 上传文件并打 project 标签。
     * metadata 中仅 project 参与权限过滤（转成 tag）；dept/sensitivity 已编码进 KB 名；
     * period/scope 不下发引擎，由 shilian 登记记录持有。
     */
    @Override
    public String uploadDocument(String datasetId, String fileName, byte[] content, Map<String, String> metadata) {
        List<String> tagIds = new ArrayList<>();
        String project = metadata.get(com.shilian.ingest.model.MetadataKeys.PROJECT);
        tagIds.add(ensureTag(datasetId, KbEncoding.projectTag(project)));

        String boundary = "----shilian" + UUID.randomUUID();
        byte[] multipart = multipart(boundary, fileName, content, tagIds);
        HttpRequest req = base(API + "/knowledge-bases/" + datasetId + "/knowledge/file")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build();
        return requireId(send(req), "上传未返回文档 id");
    }

    /** 服务端解析，不走本地推块；调用方误用直接失败，绝不静默丢内容。 */
    @Override
    public void upsertChunks(String datasetId, String documentId, List<RagflowChunk> chunks) {
        throw new UnsupportedOperationException("WeKnora 通道由 docreader 服务端解析，不接受本地推块");
    }

    @Override
    public void deleteDocument(String datasetId, String documentId) {
        try {
            send(base(API + "/knowledge-bases/" + datasetId + "/knowledge/" + documentId).DELETE().build());
        } catch (RagflowException e) {
            if (!e.getMessage().contains("404")) {
                throw e;
            }
        }
    }

    /** 轮询到 parse_status=completed；failed 抛分类原因，超时抛 RagflowException。 */
    @Override
    public ParseStats waitForIndexed(String datasetId, String documentId) {
        long deadline = System.currentTimeMillis() + parseTimeoutSeconds * 1000L;
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            JsonNode doc = findKnowledge(datasetId, documentId);
            if (doc != null) {
                String status = doc.path("parse_status").asText("");
                lastStatus = status;
                if ("completed".equals(status)) {
                    return new ParseStats(doc.path("page_count").asInt(0), doc.path("chunk_count").asInt(0));
                }
                if ("failed".equals(status)) {
                    throw new ServerSideParseException("weknora parse failed kb=" + datasetId + " doc=" + documentId,
                            reasonOf(doc.path("error_message").asText("")));
                }
            }
            sleepSeconds();
        }
        throw new RagflowException("WeKnora 解析超时（最后状态: " + lastStatus + "）");
    }

    // ---- 内部 ----

    /** 标签名→id；不存在则创建；名称竞争（409）时重查，保证幂等。 */
    private String ensureTag(String datasetId, String tagName) {
        String existing = findTagId(datasetId, tagName);
        if (existing != null) {
            return existing;
        }
        try {
            ObjectNode body = mapper.createObjectNode().put("name", tagName);
            JsonNode created = send(json("POST", API + "/knowledge-bases/" + datasetId + "/tags", body));
            return requireId(created, "标签创建未返回 id");
        } catch (RagflowException e) {
            String raced = findTagId(datasetId, tagName);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    private String findTagId(String datasetId, String tagName) {
        JsonNode list = send(get(API + "/knowledge-bases/" + datasetId + "/tags"));
        for (JsonNode t : items(list)) {
            if (tagName.equals(t.path("name").asText())) {
                return t.path("id").asText();
            }
        }
        return null;
    }

    /** 列表接口按 KB 取文档；分页未知，按 page_size 大值一次取回后线性匹配。 */
    private JsonNode findKnowledge(String datasetId, String documentId) {
        JsonNode list = send(get(API + "/knowledge-bases/" + datasetId + "/knowledge?page=1&page_size=200"));
        for (JsonNode k : items(list)) {
            if (documentId.equals(k.path("id").asText())) {
                return k;
            }
        }
        return null;
    }

    /** 服务端原文可能含文件名或内容片段，只映射为固定分类文案（越权文档不得出现在原因里）。 */
    static String reasonOf(String serverMessage) {
        String msg = serverMessage == null ? "" : serverMessage.toLowerCase();
        if (msg.contains("password") || msg.contains("encrypt")) {
            return "文档已加密，请解密后重新上传";
        }
        if (msg.contains("corrupt") || msg.contains("invalid") || msg.contains("damaged")) {
            return "文件损坏或格式不受支持，请检查后重新上传";
        }
        if (msg.contains("timeout")) {
            return "服务端解析超时，请稍后重试";
        }
        return "服务端解析失败，请检查文档内容或稍后重试";
    }

    /** WeKnora 各接口的 data 形态不一：数组、{items:[]} 或对象，统一成数组。 */
    private static List<JsonNode> items(JsonNode data) {
        List<JsonNode> out = new ArrayList<>();
        if (data == null || data.isNull()) {
            return out;
        }
        JsonNode arr = data.isArray() ? data : data.path("items");
        if (arr.isMissingNode() || !arr.isArray()) {
            arr = data.path("data");
        }
        if (arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    private static String requireId(JsonNode node, String message) {
        JsonNode id = node.path("id");
        if (id.isMissingNode() || id.asText().isBlank()) {
            throw new RagflowException(message);
        }
        return id.asText();
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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120))
                .header("X-API-Key", apiKey);
        if (!tenantId.isBlank()) {
            b.header("X-Tenant-ID", tenantId);
        }
        return b;
    }

    /** WeKnora 用 success 字段而非 code=0；返回 data 节点。 */
    private JsonNode send(HttpRequest req) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RagflowException("WeKnora 请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagflowException("WeKnora 请求被中断", e);
        }
        if (resp.statusCode() >= 400) {
            throw new RagflowException("WeKnora HTTP " + resp.statusCode());
        }
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new RagflowException("WeKnora 响应不是 JSON", e);
        }
        if (root.has("success") && !root.path("success").asBoolean(true)) {
            throw new RagflowException("WeKnora code " + root.path("error").path("code").asText("?"));
        }
        return root.path("data");
    }

    /** multipart：file 字段 + 重复 tag_ids 字段（与 spike 验证过的调用形态一致）。 */
    private static byte[] multipart(String boundary, String fileName, byte[] content, List<String> tagIds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String tagId : tagIds) {
            String part = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"tag_ids\"\r\n\r\n"
                    + tagId + "\r\n";
            out.writeBytes(part.getBytes(StandardCharsets.UTF_8));
        }
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName.replace("\"", "_") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void sleepSeconds() {
        try {
            Thread.sleep(parsePollSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagflowException("等待解析被中断", e);
        }
    }

    /** 供测试与运维自检：project 标签编码是否可被检索侧反解（p + 4 位 hex）。 */
    static boolean isEncodedProjectTag(String name) {
        return name.startsWith(KbEncoding.TAG_PREFIX_PROJECT)
                && HEX4.matcher(name.substring(KbEncoding.TAG_PREFIX_PROJECT.length())).matches();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
