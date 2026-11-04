package com.shilian.matcher.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.Scope;
import com.shilian.matcher.model.TypeStatus;
import com.shilian.matcher.support.Fixtures;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP 候选召回（WK18）。用 JDK 内置 HttpServer 模拟 retrieval-proxy，
 * 锁两件事：权限主体随请求下推（红线 1）、失败不回显文档信息（红线 2）。
 */
class HttpCandidateProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private volatile int status = 200;
    private volatile String responseBody = "{\"hits\":[],\"total\":0,\"scope_desc\":\"x\"}";

    private String base;
    private HttpCandidateProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/retrieval/search", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = provider(List.of("kb_finance"));
    }

    private HttpCandidateProvider provider(List<String> kbScope) {
        return new HttpCandidateProvider(base, kbScope, 8, 0.35);
    }

    private static RetrievalPrincipal principal() {
        return new RetrievalPrincipal("u_10231", List.of("融资部"), List.of("financing_staff"),
                "L3", List.of("PRJ-2026-CITIC"), "t_acme");
    }

    // ---- 权限下推 ----

    @Test
    void sendsPrincipalWithEverySearchRequest() throws Exception {
        provider.recall(Fixtures.makeItem(), principal());
        JsonNode body = JSON.readTree(lastBody.get());
        JsonNode p = body.path("principal");
        assertThat(p.path("user_id").asText()).isEqualTo("u_10231");
        assertThat(p.path("dept").get(0).asText()).isEqualTo("融资部");
        assertThat(p.path("roles").get(0).asText()).isEqualTo("financing_staff");
        assertThat(p.path("max_sensitivity").asText()).isEqualTo("L3");
        assertThat(p.path("projects").get(0).asText()).isEqualTo("PRJ-2026-CITIC");
        // 字段名按检索服务实际定义（retrieval_proxy.models.Principal.tenant），不是 platform.md 的 tenant_id
        assertThat(p.path("tenant").asText()).isEqualTo("t_acme");
        assertThat(body.path("mode").asText()).isEqualTo("full");
        assertThat(body.path("kb_scope").get(0).asText()).isEqualTo("kb_finance");
        assertThat(body.path("top_k").asInt()).isEqualTo(8);
        // 检索词用归一化标准名，不用原文里的约束词
        assertThat(body.path("query").asText()).isEqualTo("审计报告");
    }

    /** 红线：没有权限主体时不发请求，也不按默认密级乱查（会静默丢料或越权）。 */
    @Test
    void skipsRequestWithoutPrincipalOrScope() {
        assertThat(provider.recall(Fixtures.makeItem(), null)).isEmpty();
        assertThat(provider.recall(Fixtures.makeItem(),
                new RetrievalPrincipal("u", List.of(), List.of(), "L2", List.of(), "default"))).isEmpty();

        HttpCandidateProvider noScope = provider(List.of());
        assertThat(noScope.recall(Fixtures.makeItem(), principal())).isEmpty();
    }

    /** 无权限上下文的旧单参方法必须返回空，不能退化成匿名查询。 */
    @Test
    void legacySingleArgRecallReturnsEmpty() {
        assertThat(provider.recall(Fixtures.makeItem())).isEmpty();
    }

    // ---- hits 映射 ----

    @Test
    void mapsHitsToCandidates() throws Exception {
        status = 200;
        responseBody = """
                {"hits":[
                  {"chunk_id":"c_1","doc_id":"d_2031","doc_name":"2023年度审计报告.pdf",
                   "dept":"财务共享","sensitivity":"L3","page":37,"score":0.86,"text":"……",
                   "period":"2023","scope":"consolidated","copies":3,"stamped":true}
                ],"total":1,"scope_desc":"x"}
                """;
        List<CandidateDoc> docs = provider.recall(Fixtures.makeItem(), principal());

        assertThat(docs).hasSize(1);
        CandidateDoc d = docs.get(0);
        assertThat(d.docId()).isEqualTo("d_2031");
        assertThat(d.docName()).isEqualTo("2023年度审计报告.pdf");
        assertThat(d.period()).containsExactly("2023");
        assertThat(d.scope()).isEqualTo(Scope.CONSOLIDATED);
        assertThat(d.copies()).isEqualTo(3);
        assertThat(d.stamped()).isTrue();
        assertThat(d.pages()).containsExactly(37);
        assertThat(d.recallScore()).isEqualTo(0.86);
        assertThat(d.uri()).isEmpty();
    }

    /** 已知缺口：检索 hits 没有资料类型字段，映射必须是 UNKNOWN 而不是猜一个。 */
    @Test
    void stdTypeIsNeverInvented() throws Exception {
        status = 200;
        responseBody = """
                {"hits":[{"chunk_id":"c_1","doc_id":"d_1","doc_name":"审计报告","dept":"融资部",
                          "sensitivity":"L2","score":0.5,"text":"x"}],"total":1,"scope_desc":"x"}
                """;
        CandidateDoc d = provider.recall(Fixtures.makeItem(), principal()).get(0);

        assertThat(d.stdType()).isEqualTo(TypeStatus.UNKNOWN_TYPE);
        // 缺失元数据一律留空，交由硬约束判不通过
        assertThat(d.period()).isEmpty();
        assertThat(d.scope()).isNull();
        assertThat(d.copies()).isNull();
        assertThat(d.stamped()).isNull();
    }

    @Test
    void parsesScopeInBothContractAndChineseForms() {
        assertThat(HttpCandidateProvider.parseScope("consolidated")).isEqualTo(Scope.CONSOLIDATED);
        assertThat(HttpCandidateProvider.parseScope("合并")).isEqualTo(Scope.CONSOLIDATED);
        assertThat(HttpCandidateProvider.parseScope(" 单体 ")).isEqualTo(Scope.STANDALONE);
        assertThat(HttpCandidateProvider.parseScope("standalone")).isEqualTo(Scope.STANDALONE);
        // 解析不出按 null（判不通过），绝不猜
        assertThat(HttpCandidateProvider.parseScope("unknown-scope")).isNull();
        assertThat(HttpCandidateProvider.parseScope("")).isNull();
        assertThat(HttpCandidateProvider.parseScope(null)).isNull();
    }

    // ---- 失败处理 ----

    /** 红线：越权文档名不得进错误信息，所以只带状态码、不回显响应体。 */
    @Test
    void errorDoesNotLeakResponseBody() {
        status = 500;
        responseBody = "{\"detail\":\"含有机密文档 高管薪酬方案.xlsx\"}";

        assertThatThrownBy(() -> provider.recall(Fixtures.makeItem(), principal()))
                .isInstanceOf(HttpCandidateProvider.RetrievalUnavailableException.class)
                .hasMessage("retrieval 返回 HTTP 500")
                .hasMessageNotContaining("高管薪酬");
    }

    @Test
    void nonJsonResponseFailsCleanly() {
        status = 200;
        responseBody = "<html>网关错误 含 doc_name 报表.xlsx</html>";

        assertThatThrownBy(() -> provider.recall(Fixtures.makeItem(), principal()))
                .isInstanceOf(HttpCandidateProvider.RetrievalUnavailableException.class)
                .hasMessage("retrieval 响应不是 JSON")
                .hasMessageNotContaining("报表");
    }

    // ---- 配置 ----

    @Test
    void rejectsBlankBaseUrl() {
        assertThatThrownBy(() -> new HttpCandidateProvider("", List.of("kb"), 8, 0.35))
                .isInstanceOf(IllegalArgumentException.class);
        // 传入已含 /retrieval/search 的地址不应重复拼接（能构造即通过）
        assertThat(new HttpCandidateProvider(base + "/retrieval/search", List.of("kb"), 8, 0.35))
                .isNotNull();
    }

    @Test
    void queryFallsBackToRawTextWhenStdNameMissing() throws Exception {
        ChecklistItem item = Fixtures.item().stdName("").rawText("最近三年研发费用占比").build();
        provider.recall(item, principal());
        assertThat(JSON.readTree(lastBody.get()).path("query").asText()).isEqualTo("最近三年研发费用占比");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }
}
