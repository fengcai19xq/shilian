package com.shilian.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.shilian.dispatch.port.InMemoryWecomNotifier;
import com.shilian.dispatch.port.WecomNotifier;
import com.shilian.dispatch.routing.RoutingRules;
import com.shilian.dispatch.web.DispatchController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** 整体装配：默认配置可启动，外部系统均为内存 fake，Spring 的 Jackson 输出 snake_case。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dispatch.timeout-hours=24")
class DispatchApplicationTests {

    @Autowired
    DispatchController controller;
    @Autowired
    WecomNotifier notifier;
    @Autowired
    RoutingRules rules;
    @Autowired
    TestRestTemplate http;

    @Test
    void contextLoadsWithFakesAndSnakeCaseJson() {
        assertThat(controller).isNotNull();
        assertThat(notifier).isInstanceOf(InMemoryWecomNotifier.class);
        assertThat(rules.departments()).containsKey("finance");

        String body = http.getForObject("/dispatch/tickets?checklist_id=cl_x", String.class);
        assertThat(body).contains("\"checklist_id\":\"cl_x\"").contains("\"total\":0");
    }
}
