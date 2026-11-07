package com.tioka.orchestration.application;

import com.tioka.orchestration.infrastructure.OrchRuleRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 版本选择 + CEL 求值；候选（灰度）出错回落稳定版（坏规则保旧版）。
 */
@Service
public class RuleEvalService {
    private static final Logger log = LoggerFactory.getLogger(RuleEvalService.class);

    private final OrchRuleRepository repo;
    private final CelRuleCondition cel;

    public RuleEvalService(OrchRuleRepository repo, CelRuleCondition cel) {
        this.repo = repo;
        this.cel = cel;
    }

    public OrchRuleAppService.DryRunResult dryRun(OrchRuleVersionDO version, Map<String, Object> eventPayload) {
        Map<String, Object> event = eventMap(eventPayload);
        Map<String, String> attrs = attrsMap(eventPayload);
        try {
            boolean matched = cel.evaluate(version.getId(), version.getConditionCel(), event, attrs);
            String decision = decisionFor(version.getAcceptMode(), matched);
            return new OrchRuleAppService.DryRunResult(matched, decision,
                    matched ? "matched" : "condition_false", version.getId(), false);
        } catch (CelRuleCondition.CelEvalException e) {
            return new OrchRuleAppService.DryRunResult(false, "UNAVAILABLE", e.reason(),
                    version.getId(), false);
        }
    }

    /**
     * 按规则头选择版本并求值；灰度失败回落稳定。
     */
    public OrchRuleAppService.DryRunResult evaluateRule(String ruleId, String subjectKey,
                                                        Map<String, Object> eventPayload) {
        OrchRuleDO rule = repo.findRule(ruleId);
        if (rule == null) {
            return new OrchRuleAppService.DryRunResult(false, "IGNORE", "rule_missing", null, false);
        }
        Integer canaryPercent = null;
        if (rule.getCanaryVersionId() != null) {
            OrchRuleVersionDO canary = repo.findVersion(rule.getCanaryVersionId());
            if (canary != null) {
                canaryPercent = canary.getCanaryPercent();
            }
        }
        RuleVersionPicker.Pick pick = RuleVersionPicker.pick(
                rule.getStableVersionId(), rule.getCanaryVersionId(), canaryPercent, subjectKey);
        if (pick.versionId() == null) {
            return new OrchRuleAppService.DryRunResult(false, "IGNORE", "no_version", null, false);
        }
        OrchRuleVersionDO chosen = repo.findVersion(pick.versionId());
        if (chosen == null) {
            return new OrchRuleAppService.DryRunResult(false, "UNAVAILABLE", "version_missing",
                    pick.versionId(), false);
        }
        Map<String, Object> event = eventMap(eventPayload);
        Map<String, String> attrs = attrsMap(eventPayload);
        try {
            boolean matched = cel.evaluate(chosen.getId(), chosen.getConditionCel(), event, attrs);
            return new OrchRuleAppService.DryRunResult(matched,
                    decisionFor(chosen.getAcceptMode(), matched),
                    matched ? "matched" : "condition_false",
                    chosen.getId(), false);
        } catch (CelRuleCondition.CelEvalException e) {
            if (pick.canary() && rule.getStableVersionId() != null) {
                log.warn("灰度规则 CEL 失败，回落稳定版 rule={} canary={} reason={}",
                        ruleId, chosen.getId(), e.reason());
                OrchRuleVersionDO stable = repo.findVersion(rule.getStableVersionId());
                if (stable == null) {
                    return new OrchRuleAppService.DryRunResult(false, "UNAVAILABLE", e.reason(),
                            chosen.getId(), true);
                }
                try {
                    boolean matched = cel.evaluate(stable.getId(), stable.getConditionCel(), event, attrs);
                    return new OrchRuleAppService.DryRunResult(matched,
                            decisionFor(stable.getAcceptMode(), matched),
                            "canary_fallback:" + e.reason(),
                            stable.getId(), true);
                } catch (CelRuleCondition.CelEvalException stableEx) {
                    return new OrchRuleAppService.DryRunResult(false, "UNAVAILABLE", stableEx.reason(),
                            stable.getId(), true);
                }
            }
            return new OrchRuleAppService.DryRunResult(false, "UNAVAILABLE", e.reason(),
                    chosen.getId(), false);
        }
    }

    private static String decisionFor(String acceptMode, boolean matched) {
        if (!matched) {
            return "IGNORE";
        }
        if ("observe".equals(acceptMode)) {
            return "WOULD_ACCEPT";
        }
        if ("off".equals(acceptMode)) {
            return "IGNORE";
        }
        return "ACCEPT";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventMap(Map<String, Object> payload) {
        Map<String, Object> event = new HashMap<>();
        Object nested = payload.get("event");
        if (nested instanceof Map<?, ?> m) {
            m.forEach((k, v) -> event.put(String.valueOf(k), v));
        } else {
            if (payload.get("source") != null) {
                event.put("source", payload.get("source"));
            }
            if (payload.get("type") != null) {
                event.put("type", payload.get("type"));
            }
            if (payload.get("subjectKey") != null) {
                event.put("subjectKey", payload.get("subjectKey"));
            }
            if (payload.get("hops") != null) {
                event.put("hops", payload.get("hops"));
            }
        }
        // 故意不放入 body
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> attrsMap(Map<String, Object> payload) {
        Object attrs = payload.get("attrs");
        if (!(attrs instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        m.forEach((k, v) -> {
            if (v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }
}
