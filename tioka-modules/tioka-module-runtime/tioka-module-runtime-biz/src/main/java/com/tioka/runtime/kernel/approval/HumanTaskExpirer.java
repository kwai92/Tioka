package com.tioka.runtime.kernel.approval;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.infrastructure.persistence.RuntimeHumanTaskDO;
import com.tioka.runtime.kernel.infrastructure.HumanTaskRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * SCH-09 / ADR-066 §7.4：人工补录任务超时兜底。每分钟把到期未提交的补录任务置 EXPIRED，
 * 并把对应的 WAITING_APPROVAL Turn 置 {@code FAILED(reason=HUMAN_TIMEOUT)}。
 */
@Component
public class HumanTaskExpirer {
    private static final Logger log = LoggerFactory.getLogger(HumanTaskExpirer.class);

    private final HumanTaskRepository humanTasks;
    private final TurnRepository turns;
    private final PrincipalHolder principals;

    public HumanTaskExpirer(HumanTaskRepository humanTasks,
                            TurnRepository turns,
                            PrincipalHolder principals) {
        this.humanTasks = humanTasks;
        this.turns = turns;
        this.principals = principals;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.human-task-expirer-interval-ms:60000}")
    public void expire() {
        principals.runAs(SystemPrincipals.system(), () -> {
            List<String> ids = humanTasks.expireDue(Instant.now());
            for (String id : ids) {
                RuntimeHumanTaskDO t = humanTasks.findById(id).orElse(null);
                if (t == null || !ApprovalSubjectType.TURN.wire().equals(t.getSubjectType())) {
                    continue;
                }
                int n = turns.failWaitingApproval(t.getTurnId(), "HUMAN_TIMEOUT",
                        "人工补录超时（任务 " + id + "）");
                if (n > 0) {
                    log.debug("HumanTaskExpirer failed turn {} for expired task {}", t.getTurnId(), id);
                }
            }
            return null;
        });
    }
}
