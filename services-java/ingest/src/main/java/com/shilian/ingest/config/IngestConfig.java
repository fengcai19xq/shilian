package com.shilian.ingest.config;

import com.shilian.ingest.audit.InMemoryAuditSink;
import com.shilian.ingest.chunk.Chunker;
import com.shilian.ingest.parse.DocumentParser;
import com.shilian.ingest.ragflow.FakeRagflowClient;
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

    /** TODO(T06-real)：换成真实 RAGFlow HTTP 客户端，凭据取自 ingest.ragflow.*。 */
    @Bean
    public FakeRagflowClient ragflowClient() {
        return new FakeRagflowClient();
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
