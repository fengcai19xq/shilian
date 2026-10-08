package com.shilian.matcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.matcher.config.MatcherConfig;
import com.shilian.matcher.config.MatcherJson;
import com.shilian.matcher.config.ParserConfig;
import com.shilian.matcher.config.ScoringConfig;
import com.shilian.matcher.port.CandidateProvider;
import com.shilian.matcher.port.DecideProvider;
import com.shilian.matcher.port.NullCandidateProvider;
import com.shilian.matcher.port.RejectAllDecideProvider;
import com.shilian.matcher.service.MatcherService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    public CandidateProvider candidateProvider() {
        return new NullCandidateProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public DecideProvider decideProvider() {
        return new RejectAllDecideProvider();
    }

    @Bean
    public MatcherService matcherService(CandidateProvider candidates, DecideProvider decide) {
        return new MatcherService(candidates, decide, new MatcherConfig(ScoringConfig.fromEnv(), new ParserConfig()));
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
