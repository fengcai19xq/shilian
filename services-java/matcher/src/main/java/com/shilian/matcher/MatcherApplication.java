package com.shilian.matcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.matcher.config.MatcherConfig;
import com.shilian.matcher.config.MatcherJson;
import com.shilian.matcher.config.ParserConfig;
import com.shilian.matcher.config.ScoringConfig;
import com.shilian.matcher.port.CandidateProvider;
import com.shilian.matcher.port.DecideProvider;
import com.shilian.matcher.port.HttpCandidateProvider;
import com.shilian.matcher.port.IngestRegistryClient;
import com.shilian.matcher.port.LexicalDecideProvider;
import com.shilian.matcher.port.NullCandidateProvider;
import com.shilian.matcher.port.RejectAllDecideProvider;
import com.shilian.matcher.service.MatcherService;
import com.shilian.matcher.service.MemoryStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * matcher 服务入口。
 *
 * <p>未接入召回与 decide 时默认注入 {@link NullCandidateProvider} / {@link RejectAllDecideProvider}：
 * 所有清单项落 missing，不会产生假阳性。接入真实依赖时提供自己的 CandidateProvider / DecideProvider Bean 即可。
 */
@SpringBootApplication
public class MatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatcherApplication.class, args);
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return MatcherJson.mapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestRegistryClient ingestRegistryClient(
            @Value("${ingest.url:http://ingest:8030}") String ingestUrl) {
        return new IngestRegistryClient(ingestUrl);
    }

    @Bean
    @ConditionalOnMissingBean
    public CandidateProvider candidateProvider(
            @Value("${retrieval.url:http://retrieval:8040}") String retrievalUrl,
            @Value("${retrieval.kb-scope:}") String kbScopeStr,
            @Value("${retrieval.top-k:8}") int topK,
            @Value("${retrieval.score-threshold:0.35}") double scoreThreshold,
            IngestRegistryClient registryClient,
            @Value("${matcher.enrichment.enabled:false}") boolean enrichmentEnabled) {
        List<String> kbScope = kbScopeStr == null || kbScopeStr.isBlank()
                ? List.of()
                : List.of(kbScopeStr.split(","));
        // 成册闭环未验收前默认关闭登记富化：宁可判 missing，也不让未完成的
        // 候选属性链把分数算错。WK18 验收后再打开。
        return new HttpCandidateProvider(retrievalUrl, kbScope, topK, scoreThreshold,
                enrichmentEnabled ? registryClient : null);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecideProvider decideProvider(
            @Value("${matcher.decide.backend:lexical}") String backend) {
        return "reject".equalsIgnoreCase(backend)
                ? new RejectAllDecideProvider()
                : new LexicalDecideProvider();
    }

    @Bean
    public MatcherService matcherService(CandidateProvider candidates, DecideProvider decide,
            @Value("${matcher.store.path:}") String storePath) {
        MemoryStore store = storePath == null || storePath.isBlank()
                ? new MemoryStore()
                : new MemoryStore(Path.of(storePath));
        return new MatcherService(candidates, decide,
                new MatcherConfig(ScoringConfig.fromEnv(), new ParserConfig()), store, null);
    }

    @Bean(name = "matcherExecutor", destroyMethod = "shutdown")
    public ExecutorService matcherExecutor() {
        return Executors.newFixedThreadPool(4);
    }

    /** 安全默认服务：全部落 missing，不会产生假阳性。 */
    public static MatcherService buildDefaultService() {
        return new MatcherService(new NullCandidateProvider(), new RejectAllDecideProvider());
    }
}
