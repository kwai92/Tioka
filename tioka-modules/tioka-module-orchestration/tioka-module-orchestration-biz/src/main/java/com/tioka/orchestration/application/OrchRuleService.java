package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * V132：规则版本迁 orch_rule_version；本波仅允许列头，写路径 NOT_IMPLEMENTED（ORC-05）。
 */
@Service
public class OrchRuleService {
    private final OrchRepository repo;

    public OrchRuleService(OrchRepository repo) {
        this.repo = repo;
    }

    public List<RuleView> list() {
        return repo.listRules().stream()
                .map(r -> new RuleView(r.getId(), r.getName(), null, 0, false, Map.of()))
                .toList();
    }

    public RuleView saveDraft(String name, String eventType, Map<String, Object> action) {
        throw new BizException(ErrorCode.NOT_IMPLEMENTED, "V132：规则写入待 ORC-05（orch_rule_version）");
    }

    public RuleView publish(String ruleId) {
        throw new BizException(ErrorCode.NOT_IMPLEMENTED, "V132：规则发布待 ORC-05");
    }

    public OrchRuleDO findPublished(String eventType) {
        return null;
    }

    public Map<String, Object> parseAction(OrchRuleDO rule) {
        return Map.of();
    }

    public record RuleView(String id, String name, String eventType, int version,
                           boolean published, Map<String, Object> action) {}
}
