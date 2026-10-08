package com.shilian.dispatch.config;

import com.shilian.dispatch.service.DispatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时扫描超时工单，提醒负责人并升级到部门负责人。 */
@Component
public class EscalationScheduler {

    private final DispatchService service;

    public EscalationScheduler(DispatchService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${dispatch.escalation.scan-interval-ms:300000}",
            initialDelayString = "${dispatch.escalation.scan-interval-ms:300000}")
    public void scan() {
        service.escalateOverdue();
    }
}
