package com.tioka.governance.application.alert;

import com.foundation.common.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.governance.api.alert.AlertRuleRef;
import com.tioka.governance.api.alert.AlertSignalProvider;
import com.tioka.governance.api.alert.AlertSignalProvider.AlertObservation;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GOV-13 告警求值引擎（ADR-041 §5.3「求值」）。
 *
 * <p>每 30 秒由一个实例执行（Redis SETNX 锁，多实例互斥）；按启用规则逐个条件向
 * {@link AlertSignalProvider} 取观测，去重后写入 {@code ops_alert_event}，并按规则渠道与静默时段发出通知。
 *
 * <p>失败软降级：任何异常只记日志，不影响其它规则与调度；Redis 缺席时退化为单实例执行。
 */
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);
    private static final String LOCK_KEY = "alert:evaluate:lock";

    /** 本实例的锁持有者标识（多实例互斥；释放时校验相等再删，ENG-05 用 {@link Ids}）。 */
    private final String lockHolder = Ids.newId("alertlock");

    private final OpsAlertRepository repo;
    private final AlertRuleService ruleService;
    private final List<AlertSignalProvider> providers;
    private final AlertEngine engine;
    private final AlertEmitter emitter;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final AlertProperties props;
    private final PrincipalHolder principals;

    public AlertEvaluator(OpsAlertRepository repo,
                          AlertRuleService ruleService,
                          List<AlertSignalProvider> providers,
                          AlertEngine engine,
                          AlertEmitter emitter,
                          ObjectProvider<StringRedisTemplate> redis,
                          AlertProperties props,
                          PrincipalHolder principals) {
        this.repo = repo;
        this.ruleService = ruleService;
        this.providers = providers == null ? List.of() : providers;
        this.engine = engine;
        this.emitter = emitter;
        this.redis = redis;
        this.props = props;
        this.principals = principals;
    }

    /** 定时求值入口（{@code tioka.alerts.evaluate-interval-ms}，默认 30s）。 */
    @Scheduled(fixedDelayString = "${tioka.alerts.evaluate-interval-ms:30000}")
    public void scheduledEvaluate() {
        evaluate();
    }

    /** 单次求值（可测试直接调用）。 */
    public void evaluate() {
        String holder = lockHolder;
        StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
        if (template != null && !tryLock(template, holder)) {
            return;
        }
        try {
            runAsSystem(this::runOnce);
        } catch (RuntimeException e) {
            log.warn("告警求值失败（忽略）: {}", e.toString());
        } finally {
            if (template != null) {
                release(template, holder);
            }
        }
    }

    private void runOnce() {
        try {
            ruleService.seedBuiltinRules();
        } catch (RuntimeException e) {
            log.warn("内置告警规则播种失败（忽略）: {}", e.toString());
        }
        Instant now = Instant.now();
        List<OpsAlertRuleDO> rules;
        try {
            rules = repo.listEnabledRules();
        } catch (RuntimeException e) {
            log.warn("读取告警规则失败（忽略）: {}", e.toString());
            return;
        }
        for (OpsAlertRuleDO rule : rules) {
            try {
                evaluateRule(rule, now);
            } catch (RuntimeException e) {
                log.warn("规则求值失败（忽略） rule={}: {}", rule.getId(), e.toString());
            }
        }
    }

    private void evaluateRule(OpsAlertRuleDO rule, Instant now) {
        AlertRuleRef ref = AlertViews.toRef(rule);
        boolean eventLike = ref.conditionType().isEventCondition();
        Map<String, String> lastStates = new HashMap<>();
        for (AlertSignalProvider provider : providers) {
            if (!provider.supports(ref.conditionType())) {
                continue;
            }
            List<AlertObservation> observations;
            try {
                observations = provider.observe(ref, now);
            } catch (RuntimeException e) {
                log.warn("信号源求值失败（忽略） provider={} rule={}: {}",
                        provider.getClass().getSimpleName(), rule.getId(), e.toString());
                continue;
            }
            if (observations == null || observations.isEmpty()) {
                continue;
            }
            for (AlertObservation obs : observations) {
                String targetId = obs.targetId() == null ? ref.targetId() : obs.targetId();
                if (!eventLike) {
                    lastStates.computeIfAbsent(targetId,
                            t -> lastState(rule.getId(), t, rule.getConditionType()));
                }
            }
            for (AlertEngine.Outcome outcome : engine.evaluate(ref, observations, lastStates)) {
                if (emitter.emit(rule, outcome, eventLike)) {
                    lastStates.put(outcome.targetId(), outcome.state().name());
                }
            }
        }
    }

    private String lastState(String ruleId, String targetId, String conditionType) {
        return repo.findLatestEvent(ruleId, targetId, conditionType)
                .map(OpsAlertEventDO::getState)
                .orElse(null);
    }

    private void runAsSystem(Runnable body) {
        if (principals == null) {
            body.run();
            return;
        }
        principals.runAs(SystemPrincipals.system(), body);
    }

    private boolean tryLock(StringRedisTemplate template, String holder) {
        try {
            Boolean ok = template.opsForValue()
                    .setIfAbsent(LOCK_KEY, holder, java.time.Duration.ofMillis(props.getLockTtlMs()));
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.warn("获取告警求值锁失败，退化为单实例执行: {}", e.toString());
            return true;
        }
    }

    private void release(StringRedisTemplate template, String holder) {
        try {
            if (holder.equals(template.opsForValue().get(LOCK_KEY))) {
                template.delete(LOCK_KEY);
            }
        } catch (RuntimeException e) {
            log.debug("释放告警求值锁失败（TTL 兜底）: {}", e.toString());
        }
    }
}
