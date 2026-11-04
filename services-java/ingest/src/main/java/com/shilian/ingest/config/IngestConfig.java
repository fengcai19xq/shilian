package com.shilian.ingest.config;

import com.shilian.ingest.audit.InMemoryAuditSink;
import com.shilian.ingest.chunk.Chunker;
import com.shilian.ingest.parse.DocumentParser;
import com.shilian.ingest.ragflow.FakeRagflowClient;
import com.shilian.ingest.ragflow.HttpRagflowClient;
import com.shilian.ingest.ragflow.HttpWeKnoraClient;
import com.shilian.ingest.ragflow.RagflowClient;
import com.shilian.ingest.store.InMemoryDocumentRepository;
import java.time.Clock;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class IngestConfig {

    @Bean
    public InMemoryDocumentRepository documentRepository() {
        return new InMemoryDocumentRepository();
    }

    @Bean
    public InMemoryAuditSink auditSink() {
        return new InMemoryAuditSink();
    }

    /**
     * 按 ingest.sink 选择推送通道（WK10）：
     *  weknora → docreader 服务端解析；ragflow（默认）→ 本地解析后推块。
     * 未配置对应 base-url 时退化为内存实现（本地开发与单测）。
     */
    @Bean
    public RagflowClient ragflowClient(IngestProperties props) {
        if (props.weknoraSink()) {
            String baseUrl = props.weknora().baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                return new FakeRagflowClient();
            }
            return new HttpWeKnoraClient(baseUrl, props.weknora().apiKey(), props.weknora().tenantId(),
                    props.weknora().parseTimeoutSeconds(), props.weknora().parsePollSeconds(),
                    props.weknora().embeddingModel());
        }
        String baseUrl = props.ragflow().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new FakeRagflowClient();
        }
        return new HttpRagflowClient(baseUrl, props.ragflow().apiKey());
    }

    @Bean
    public DocumentParser documentParser() {
        return new DocumentParser();
    }

    @Bean
    public Chunker chunker(IngestProperties props) {
        return new Chunker(props.chunk().size(), props.chunk().overlap());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Executor ingestExecutor(IngestProperties props) {
        if (!props.async()) {
            return Runnable::run;
        }
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(200);
        pool.setThreadNamePrefix("ingest-");
        return pool;
    }
}
