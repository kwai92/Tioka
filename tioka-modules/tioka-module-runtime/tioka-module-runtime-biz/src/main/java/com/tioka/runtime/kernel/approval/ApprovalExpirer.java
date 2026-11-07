package com.tioka.runtime.kernel.approval;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** 每分钟过期 PENDING 审批并触发 subject handler（M1-02）。 */
@Component
public class ApprovalExpirer {
    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirer.class);

    private final ApprovalRepository approvals;
    private final ApprovalDecideService decideService;
    private final PrincipalHolder principals;

    public ApprovalExpirer(ApprovalRepository approvals,
                           ApprovalDecideService decideService,
                           PrincipalHolder principals) {
        this.approvals = approvals;
        this.decideService = decideService;
        this.principals = principals;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.approval-expirer-interval-ms:60000}")
    public void expire() {
        principals.runAs(SystemPrincipals.system(), () -> {
            List<String> ids = approvals.expireDue(Instant.now());
            for (String id : ids) {
                RuntimeApprovalDO a = approvals.findById(id).orElse(null);
                if (a == null) {
                    continue;
                }
                try {
                    decideService.notifyDecided(a);
                } catch (Exception e) {
                    log.warn("approval expire handler failed id={}: {}", id, e.toString());
                }
            }
            return null;
        });
    }
}
