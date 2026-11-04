package com.shilian.wecomsync.weknora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * WeKnora 身份侧 HTTP 客户端（WK14）。鉴权用 X-API-Key（空间级 Key，需 manage_kbs / manage_members 能力）。
 *
 * 端点核对状态：
 *  - KB 读写（GET/POST /api/v1/knowledge-bases）：已在 53 测试环境实测通过，形态可靠。
 *  - 空间成员（/api/v1/tenants/{id}/members）：官方文档只登记了 /tenants 分组与
 *    manage_members 能力位，具体子路径与请求体尚未现场核对。因此成员方法默认按
 *    「未核对」处理（抛 UnsupportedOperationException），核对后再打开 {@link #membersEnabled}。
 *    宁可功能不生效，也不要凭猜测向真实环境发出写请求。
 */
public class HttpWeknoraIdentityClient implements WeknoraIdentityClient {

    private static final String API = "/api/v1";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String tenantId;
    /** 成员端点核对完成前置为 false，使成员方法明确失败而非盲发请求。 */
    private final boolean membersEnabled;
    private final String embeddingModel;
    /** 模型名→id 只解析一次缓存起来。 */
    private volatile String embeddingModelId;

    public HttpWeknoraIdentityClient(String baseUrl, String apiKey, String tenantId, String embeddingModel) {
        this(baseUrl, apiKey, tenantId, embeddingModel, false,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public HttpWeknoraIdentityClient(String baseUrl, String apiKey, String tenantId, String embeddingModel,
            boolean membersEnabled, HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("weknora.base-url / api-key 不能为空");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            // 与入库侧同源：投影建出的 KB 若没绑向量模型，ingest 会拒绝复用，
            // 且这类 KB 无法事后补绑，文档必然解析失败。配置缺失当场拦住。
            throw new IllegalArgumentException("weknora.embedding-model 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.tenantId = tenantId == null ? "" : tenantId.strip();
        this.embeddingModel = embeddingModel.strip();
        this.membersEnabled = membersEnabled;
        this.http = http;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<String> listKbNames() {
        List<String> names = new ArrayList<>();
        for (JsonNode kb : items(send(get(API + "/knowledge-bases")))) {
            names.add(kb.path("name").asText(""));
        }
        return names;
    }

    /**
     * 同名已存在时返回既有 id。先查后建，避免依赖上游是否做名称唯一约束。
     *
     * 建库必须绑定向量模型（2026-11-04 现场实测）：不绑定的 KB 里文档解析必然失败，
     * 且 PUT 补绑不生效——只能删除重建。复用时发现未绑定同样提前报错，
     * 与 ingest 侧 createDataset 的校验口径一致。
     */
    @Override
    public String createKb(String name) {
        String modelId = resolveEmbeddingModelId();
        for (JsonNode kb : items(send(get(API + "/knowledge-bases")))) {
            if (name.equals(kb.path("name").asText())) {
                if (kb.path("embedding_model_id").asText("").isBlank()) {
                    throw new WeknoraApiException("已存在的知识库 " + name
                            + " 未绑定向量模型且无法补绑，请在 WeKnora 删除该 KB 后重试");
                }
                return kb.path("id").asText();
            }
        }
        ObjectNode body = mapper.createObjectNode().put("name", name).put("type", "document");
        body.put("description", "shilian 预置（密级×部门复合命名，勿手动改名）");
        body.put("embedding_model_id", modelId);
        JsonNode created = send(json("POST", API + "/knowledge-bases", body));
        String id = created.path("id").asText("");
        if (id.isBlank()) {
            throw new WeknoraApiException("创建 KB 未返回 id");
        }
        return id;
    }

    /** 模型名→id：只认 active 的 Embedding 模型，解析不到当场报错。 */
    private String resolveEmbeddingModelId() {
        String cached = embeddingModelId;
        if (cached != null) {
            return cached;
        }
        for (JsonNode m : items(send(get(API + "/models")))) {
            if (!"Embedding".equals(m.path("type").asText())) {
                continue;
            }
            if (!"active".equals(m.path("status").asText("active"))) {
                continue;
            }
            if (embeddingModel.equals(m.path("name").asText())) {
                String id = m.path("id").asText("");
                if (id.isBlank()) {
                    throw new WeknoraApiException("WeKnora 向量模型未返回 id");
                }
                embeddingModelId = id;
                return id;
            }
        }
        throw new WeknoraApiException("WeKnora 空间内找不到可用的向量模型: " + embeddingModel);
    }

    @Override
    public Optional<Member> findMember(String externalUserId) {
        requireMembers();
        String path = API + "/tenants/" + tenantPath() + "/members?page=1&page_size=200";
        for (JsonNode m : items(send(get(path)))) {
            if (externalUserId.equals(m.path("external_user_id").asText())) {
                return Optional.of(new Member(externalUserId, m.path("email").asText(""),
                        m.path("role").asText("")));
            }
        }
        return Optional.empty();
    }

    @Override
    public void upsertMember(String externalUserId, String email, String name, String role) {
        requireMembers();
        ObjectNode body = mapper.createObjectNode()
                .put("external_user_id", externalUserId)
                .put("email", email)
                .put("name", name)
                .put("role", role);
        send(json("POST", API + "/tenants/" + tenantPath() + "/members", body));
    }

    // ---- 内部 ----

    private void requireMembers() {
        if (!membersEnabled) {
            throw new UnsupportedOperationException("空间成员端点待现场核对，暂不下发");
        }
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("weknora.tenant-id 未配置，无法定位空间");
        }
    }

    private String tenantPath() {
        return tenantId.isBlank() ? "current" : tenantId;
    }

    private static List<JsonNode> items(JsonNode data) {
        List<JsonNode> out = new ArrayList<>();
        if (data == null || data.isNull()) {
            return out;
        }
        JsonNode arr = data.isArray() ? data : data.path("items");
        if (!arr.isArray()) {
            arr = data.path("data");
        }
        if (arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    private HttpRequest get(String path) {
        return base(path).GET().build();
    }

    private HttpRequest json(String method, String path, JsonNode body) {
        return base(path).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest.Builder base(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("X-API-Key", apiKey);
        if (!tenantId.isBlank()) {
            b.header("X-Tenant-ID", tenantId);
        }
        return b;
    }

    /** 只回显状态码与错误码，不回显服务端原文（可能含成员邮箱等信息）。 */
    private JsonNode send(HttpRequest req) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WeknoraApiException("WeKnora 请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeknoraApiException("WeKnora 请求被中断", e);
        }
        if (resp.statusCode() >= 400) {
            throw new WeknoraApiException("WeKnora HTTP " + resp.statusCode());
        }
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new WeknoraApiException("WeKnora 响应不是 JSON", e);
        }
        if (root.has("success") && !root.path("success").asBoolean(true)) {
            throw new WeknoraApiException("WeKnora error " + root.path("error").path("code").asText("?"));
        }
        return root.path("data");
    }
}
