package com.shilian.dispatch.config;

import com.shilian.dispatch.port.AuditSink;
import com.shilian.dispatch.port.DocumentVerifier;
import com.shilian.dispatch.port.InMemoryAuditSink;
import com.shilian.dispatch.port.InMemoryDocumentVerifier;
import com.shilian.dispatch.port.InMemoryRematchNotifier;
import com.shilian.dispatch.port.InMemoryTicketRepository;
import com.shilian.dispatch.port.InMemoryWecomNotifier;
import com.shilian.dispatch.port.RematchNotifier;
import com.shilian.dispatch.port.TicketRepository;
import com.shilian.dispatch.port.WecomNotifier;
import com.shilian.dispatch.routing.RoutingRules;
import com.shilian.dispatch.service.DispatchService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 装配。外部系统（企微、文档库、matcher、审计库、工单库）默认全部是内存 fake，
 * 接真实系统时提供同类型的 Bean 即可覆盖（@ConditionalOnMissingBean）。
 */
@Configuration
public class DispatchConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DispatchSettings dispatchSettings(
            @Value("${dispatch.timeout-hours:48}") double timeoutHours,
            @Value("${dispatch.upload-url-base:}") String uploadUrlBase) {
        return new DispatchSettings(timeoutHours, uploadUrlBase);
    }

    @Bean
    public RoutingRules routingRules(
            ResourceLoader loader, @Value("${dispatch.routing-file:classpath:dispatch-routing.yml}") String location)
            throws IOException {
        Resource resource = loader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return RoutingRules.load(in);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public WecomNotifier wecomNotifier() {
        return new InMemoryWecomNotifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditSink auditSink() {
        return new InMemoryAuditSink();
    }

    @Bean
    @ConditionalOnMissingBean
    public TicketRepository ticketRepository() {
        return new InMemoryTicketRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentVerifier documentVerifier() {
        return new InMemoryDocumentVerifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public RematchNotifier rematchNotifier() {
        return new InMemoryRematchNotifier();
    }

    @Bean
    public DispatchService dispatchService(
            TicketRepository repo, RoutingRules rules, WecomNotifier notifier, AuditSink audit,
            DocumentVerifier verifier, RematchNotifier rematch, Clock clock, DispatchSettings settings) {
        return new DispatchService(repo, rules, notifier, audit, verifier, rematch, clock, settings);
    }
}
