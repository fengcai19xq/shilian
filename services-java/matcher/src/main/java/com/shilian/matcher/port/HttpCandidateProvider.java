package com.shilian.matcher.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.Scope;
import com.shilian.matcher.model.TypeStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 经 HTTP 调 services/retrieval-proxy 召回候选（WK18 / contracts/matching.md）。
 *
 * <p>权限主体随检索请求一起下发（红线 1）：dept / max_sensitivity / projects / tenant
 * 全部进请求体，过滤在检索服务内部完成，本类<b>不做任何取回后过滤</b>。
 *
 * <p>已知缺口（不在本类伪造）：retrieval 的 {@code hits[]} 没有资料类型字段，
 * 因此 {@code stdType} 一律映射为 {@link TypeStatus#UNKNOWN_TYPE}。配合
 * {@link com.shilian.matcher.core.HardConstraints} 的类型硬约束，真实链路上
 * 清单项会因 type_mismatch 落 rejected —— 这是「宁缺勿错」的预期行为，
 * 等 std_type 来源打通（见任务备注）后才会真正 matched。绝不猜类型造出假阳性。
 *
 * <p>错误信息只含状态码，不回显文档标题 / 片段内容（越权文档名也不得外泄）。
 */
public class HttpCandidateProvider implements CandidateProvider {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String searchUrl;
    private final List<String> kbScope;
    private final int topK;
    private final double scoreThreshold;

    public HttpCandidateProvider(String baseUrl, List<String> kbScope, int topK, double scoreThreshold) {
        this(baseUrl, kbScope, topK, scoreThreshold,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public HttpCandidateProvider(String baseUrl, List<String> kbScope, int topK, double scoreThreshold,
            HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("retrieval.base-url 不能为空");
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 检索服务把接口挂在 /retrieval/search（含已在内），这里只补一次前缀，避免双写
        this.searchUrl = trimmed.endsWith("/retrieval/search") ? trimmed : trimmed + "/retrieval/search";
        this.kbScope = kbScope == null ? List.of() : List.copyOf(kbScope);
        this.topK = topK <= 0 ? 8 : topK;
        this.scoreThreshold = scoreThreshold <= 0 ? 0.35 : scoreThreshold;
        this.http = http;
    }

    /** 无权限上下文：真实链路不应走这里，返回空让该项落 missing，不按默认密级乱查。 */
    @Override
    public List<CandidateDoc> recall(ChecklistItem item) {
        return List.of();
    }

    @Override
    public List<CandidateDoc> recall(ChecklistItem item, RetrievalPrincipal principal) {
        if (principal == null || principal.hasNoDept() || kbScope.isEmpty()) {
            // 无部门授权或无可检索知识库：检索侧必然返回空，这里连请求都不发
            return List.of();
        }
        JsonNode resp = search(buildRequest(item, principal));
        List<CandidateDoc> out = new ArrayList<>();
        for (JsonNode hit : resp.path("hits")) {
            out.add(toCandidate(hit));
        }
        return out;
    }

    // ---- 请求构造 ----

    private ObjectNode buildRequest(ChecklistItem item, RetrievalPrincipal principal) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", queryOf(item));
        ObjectNode p = body.putObject("principal");
        p.put("user_id", principal.userId());
        putStrings(p.putArray("dept"), principal.dept());
        putStrings(p.putArray("roles"), principal.roles());
        p.put("max_sensitivity", principal.maxSensitivity());
        putStrings(p.putArray("projects"), principal.projects());
        p.put("tenant", principal.tenant());
        putStrings(body.putArray("kb_scope"), kbScope);
        body.put("top_k", topK);
        body.put("score_threshold", scoreThreshold);
        body.put("mode", "full");
        return body;
    }

    /** 检索词优先用归一化标准名，回退清单原文；都不做类型猜测。 */
    private static String queryOf(ChecklistItem item) {
        String std = item.stdName();
        if (std != null && !std.isBlank()) {
            return std;
        }
        return item.rawText() == null ? "" : item.rawText();
    }

    // ---- 响应映射 ----

    private CandidateDoc toCandidate(JsonNode hit) {
        List<String> period = new ArrayList<>();
        String periodText = hit.path("period").asText("");
        if (!periodText.isBlank()) {
            period.add(periodText);
        }
        List<Integer> pages = new ArrayList<>();
        if (hit.hasNonNull("page")) {
            pages.add(hit.path("page").asInt());
        }
        double score = hit.path("score").asDouble(0.0);
        return new CandidateDoc(
                hit.path("doc_id").asText(""),
                hit.path("doc_name").asText(""),
                "", // hits 无 uri；manifest 侧按 doc://{doc_id} 兜底
                TypeStatus.UNKNOWN_TYPE, // std_type 缺口：不伪造，见类注释
                period,
                parseScope(hit.path("scope").asText("")),
                hit.hasNonNull("copies") ? hit.path("copies").asInt() : null,
                hit.hasNonNull("stamped") ? hit.path("stamped").asBoolean() : null,
                pages,
                score,
                0.0); // form_score 非检索字段
    }

    /** 口径有两种写法（契约枚举 consolidated/standalone，入库可能是中文 单体/合并）；解析不出按 null（判不通过）。 */
    static Scope parseScope(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.strip()) {
            case "consolidated", "合并", "合并口径" -> Scope.CONSOLIDATED;
            case "standalone", "单体", "母公司", "单体口径" -> Scope.STANDALONE;
            default -> null;
        };
    }

    // ---- 传输 ----

    private JsonNode search(ObjectNode body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(searchUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RetrievalUnavailableException("retrieval 请求失败: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetrievalUnavailableException("retrieval 请求被中断");
        }
        if (resp.statusCode() >= 400) {
            // 只带状态码，不回显响应体（可能含 doc_name 等存在性信号）
            throw new RetrievalUnavailableException("retrieval 返回 HTTP " + resp.statusCode());
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new RetrievalUnavailableException("retrieval 响应不是 JSON");
        }
    }

    private static void putStrings(ArrayNode arr, List<String> values) {
        for (String v : values) {
            arr.add(v);
        }
    }

    /** 检索不可用。匹配任务据此整体失败，绝不静默把「查不到」当成「没有」。 */
    public static class RetrievalUnavailableException extends RuntimeException {
        public RetrievalUnavailableException(String message) {
            super(message);
        }
    }
}
