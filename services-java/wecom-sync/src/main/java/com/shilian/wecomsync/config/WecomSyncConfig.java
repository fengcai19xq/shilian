package com.shilian.wecomsync.config;

import com.shilian.wecomsync.audit.AuditSink;
import com.shilian.wecomsync.audit.InMemoryAuditSink;
import com.shilian.wecomsync.identity.PrincipalResolver;
import com.shilian.wecomsync.store.DepartmentRepository;
import com.shilian.wecomsync.store.GrantRepository;
import com.shilian.wecomsync.store.InMemoryDepartmentRepository;
import com.shilian.wecomsync.store.InMemoryGrantRepository;
import com.shilian.wecomsync.store.InMemoryUserRepository;
import com.shilian.wecomsync.store.UserRepository;
import com.shilian.wecomsync.wecom.FakeWecomClient;
import com.shilian.wecomsync.wecom.WecomClient;
import com.shilian.wecomsync.weknora.FakeWeknoraIdentityClient;
import com.shilian.wecomsync.weknora.HttpWeknoraIdentityClient;
import com.shilian.wecomsync.weknora.ProjectionProperties;
import com.shilian.wecomsync.weknora.WeknoraIdentityClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 默认装配：外部系统与存储全部用内存实现。
 * 接入真实企微 / 数据库时，只需另行声明对应接口的 Bean，这里的默认实现会自动让位。
 */
@Configuration
public class WecomSyncConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(WecomClient.class)
    public FakeWecomClient wecomClient() {
        return FakeWecomClient.demo();
    }

    @Bean
    @ConditionalOnMissingBean(UserRepository.class)
    public InMemoryUserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    @ConditionalOnMissingBean(DepartmentRepository.class)
    public InMemoryDepartmentRepository departmentRepository() {
        return new InMemoryDepartmentRepository();
    }

    @Bean
    @ConditionalOnMissingBean(GrantRepository.class)
    public InMemoryGrantRepository grantRepository() {
        return new InMemoryGrantRepository();
    }

    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    public InMemoryAuditSink auditSink() {
        return new InMemoryAuditSink();
    }

    @Bean
    public PrincipalResolver principalResolver(
            @Value("${identity.default-max-sensitivity:L2}") String defaultMaxSensitivity) {
        return new PrincipalResolver(defaultMaxSensitivity);
    }

    /**
     * WeKnora 身份客户端（WK14）：weknora.enabled 且凭据齐备时走真实 HTTP，
     * 否则退回内存实现并置为不可用——此时投影只出计划、不触碰后端。
     */
    @Bean
    @ConditionalOnMissingBean(WeknoraIdentityClient.class)
    public WeknoraIdentityClient weknoraIdentityClient(ProjectionProperties props) {
        if (props.active()) {
            return new HttpWeknoraIdentityClient(props.baseUrl(), props.apiKey(), props.tenantId());
        }
        FakeWeknoraIdentityClient offline = new FakeWeknoraIdentityClient();
        offline.setAvailable(false);
        return offline;
    }
}
