package com.tioka.orchestration.application.pause;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.notify.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 规则自动暂停 / 恢复（ORC-07，M2-01「规则自动暂停」）。
 *
 * <p>失败计数：Run 以 {@code FAILED} 终态结束时 {@code consecutive_failures + 1}，
 * {@code SUCCEEDED} 清零；达到阈值（{@code tioka.orch.rule.pause-threshold}，默认 5，
 * ADR-020「同一规则连续 5 次 Run 失败则自动暂停规则」）→ 规则进入暂停态
 * （{@code paused_at}/{@code paused_reason}，V131 列），派发入口
 * {@code OrchEngineRepository#findActiveRulesForEventType} 跳过暂停规则。
 *
 * <p>暂停事件写审计（{@code orch.rule.pause}，system actor）并经通知端口（约束 F21）
 * 告警规则负责人；恢复为显式入口（{@code orch.rule.resume}，REST + @Audited）。
 * 列存于 {@code orch_rule}（V131 已带入），本批零迁移。
 */
@Service
public class RulePauseService {

    private static final Logger log = LoggerFactory.getLogger(RulePauseService.class);

    public static final String PAUSE_REASON_CONSECUTIVE_FAILURES = "consecutive_failures";
    private static final String NOTIFY_CHANNEL = "orch";
    private static final String NOTIFY_TEMPLATE = "orch.rule.auto_paused";

    private final OrchRuleRepository repo;
    private final ObjectProvider<NotificationPort> notifications;
    private final AuditApi audit;
    private final int pauseThreshold;

    public RulePauseService(OrchRuleRepository repo,
                            ObjectProvider<NotificationPort> notifications,
                            AuditApi audit,
                            @Value("${tioka.orch.rule.pause-threshold:5}") int pauseThreshold) {
        this.repo = repo;
        this.notifications = notifications;
        this.audit = audit;
        this.pauseThreshold = pauseThreshold;
    }

    /**
     * Run 终态回调（失败计数的唯一统计点）：SUCCEEDED 清零，FAILED 累加并按阈值自动暂停，
     * 其余终态（CANCELLED / UNCERTAIN 等）不计数。幂等：已暂停规则不再累加。
     */
    public void recordRunOutcome(String ruleVersionId, String terminalStatus) {
        if (ruleVersionId == null || terminalStatus == null) {
            return;
        }
        OrchRuleVersionDO version = repo.findVersion(ruleVersionId);
        if (version == null) {
            return;
        }
        OrchRuleDO rule = repo.findRule(version.getRuleId());
        if (rule == null) {
            return;
        }
        if (OrchApi.RunStatus.SUCCEEDED.name().equals(terminalStatus)) {
            if (rule.getConsecutiveFailures() != null && rule.getConsecutiveFailures() > 0) {
                rule.setConsecutiveFailures(0);
                repo.setConsecutiveFailures(rule.getId(), 0);
            }
            return;
        }
        if (!OrchApi.RunStatus.FAILED.name().equals(terminalStatus) || isPaused(rule)) {
            return;
        }
        int failures = (rule.getConsecutiveFailures() == null ? 0 : rule.getConsecutiveFailures()) + 1;
        rule.setConsecutiveFailures(failures);
        if (failures >= pauseThreshold) {
            LocalDateTime now = LocalDateTime.now();
            rule.setPausedAt(now);
            rule.setPausedReason(PAUSE_REASON_CONSECUTIVE_FAILURES);
            repo.markPaused(rule.getId(), now, PAUSE_REASON_CONSECUTIVE_FAILURES, failures);
            auditAutoPause(rule, failures);
            notifyOwner(rule, failures);
            log.warn("规则连续失败 {} 次达到阈值 {}，已自动暂停 rule={} reason={}",
                    failures, pauseThreshold, rule.getId(), PAUSE_REASON_CONSECUTIVE_FAILURES);
        } else {
            repo.setConsecutiveFailures(rule.getId(), failures);
            log.info("规则 Run 失败：连续失败 {}/{} rule={}", failures, pauseThreshold, rule.getId());
        }
    }

    /** 显式恢复：清暂停标记与失败计数，规则重新参与派发。 */
    public void resume(String ruleId) {
        OrchRuleDO rule = repo.findRule(ruleId);
        if (rule == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "rule 不存在");
        }
        if (!isPaused(rule)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则未处于暂停状态");
        }
        repo.clearPause(ruleId);
        log.info("规则已恢复 rule={} 原暂停原因={}", ruleId, rule.getPausedReason());
    }

    public static boolean isPaused(OrchRuleDO rule) {
        return rule != null && rule.getPausedAt() != null;
    }

    private void auditAutoPause(OrchRuleDO rule, int failures) {
        try {
            audit.record(AuditEvent.builder()
                    .ts(Instant.now())
                    .workspaceId(rule.getWorkspaceId())
                    .actorType("system")
                    .actorId("system")
                    .action("orch.rule.pause")
                    .resourceType("orch_rule")
                    .resourceId(rule.getId())
                    .outcome(AuditOutcome.SUCCESS)
                    .reason(PAUSE_REASON_CONSECUTIVE_FAILURES)
                    .details(Map.of(
                            "consecutiveFailures", failures,
                            "threshold", pauseThreshold,
                            "ruleName", rule.getName() == null ? "" : rule.getName()))
                    .build());
        } catch (RuntimeException e) {
            log.warn("自动暂停审计写入失败（忽略）rule={}: {}", rule.getId(), e.toString());
        }
    }

    private void notifyOwner(OrchRuleDO rule, int failures) {
        try {
            NotificationPort port = notifications.getIfAvailable();
            if (port == null || rule.getOwnerId() == null) {
                return;
            }
            port.notify(new NotificationPort.NotificationCommand(
                    NOTIFY_CHANNEL,
                    rule.getOwnerId(),
                    NOTIFY_TEMPLATE,
                    Map.of(
                            "ruleId", rule.getId(),
                            "ruleName", rule.getName() == null ? "" : rule.getName(),
                            "consecutiveFailures", failures,
                            "threshold", pauseThreshold),
                    "orch-rule-paused:" + rule.getId()));
        } catch (RuntimeException e) {
            log.warn("自动暂停通知发送失败（忽略）rule={}: {}", rule.getId(), e.toString());
        }
    }
}
