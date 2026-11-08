package com.tioka.orchestration.timer;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每实例扫描到期 orch_timer（ORC-06）；行级 SKIP LOCKED 防双触发。
 * 以 SYSTEM Principal 扫全空间表（ScopeLine 旁路）。
 */
@Component
public class OrchTimerPoller {

    private static final Logger log = LoggerFactory.getLogger(OrchTimerPoller.class);

    private final OrchTimerService timers;
    private final PrincipalHolder principals;
    private final boolean enabled;

    public OrchTimerPoller(OrchTimerService timers,
                           PrincipalHolder principals,
                           @Value("${tioka.orch.timer-poll-enabled:true}") boolean enabled) {
        this.timers = timers;
        this.principals = principals;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${tioka.orch.timer-poll-interval-ms:5000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        principals.runAs(SystemPrincipals.system(), () -> {
            int n = timers.pollOnce();
            if (n > 0) {
                log.debug("orch timer poll fired={}", n);
            }
            return null;
        });
    }
}
