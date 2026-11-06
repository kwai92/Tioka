package com.tioka.orchestration.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class OrchRuleService {
    private final OrchRepository repo;
    private final ObjectMapper mapper;

    public OrchRuleService(OrchRepository repo, ObjectMapper mapper) {
        this.mapper = mapper;
        this.repo = repo;
    }

    public RuleView saveDraft(String name, String eventType, Map<String, Object> action) {
        if (eventType == null || eventType.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "eventType 必填");
        }
        String id = Ids.newId("rule");
        OrchRuleDO row = new OrchRuleDO();
        row.setId(id);
        row.setName(name == null || name.isBlank() ? id : name);
        row.setEventType(eventType);
        row.setVersion(repo.nextRuleVersion(eventType));
        row.setPublished(false);
        row.setActionJson(toJson(action == null ? Map.of("type", "create_run") : action));
        repo.insertRule(row);
        return toView(row);
    }

    public RuleView publish(String ruleId) {
        OrchRuleDO row = repo.findRule(ruleId);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "rule 不存在");
        row.setPublished(true);
        repo.updateRule(row);
        return toView(row);
    }

    public List<RuleView> list() {
        return repo.listRules().stream().map(this::toView).toList();
    }

    public OrchRuleDO findPublished(String eventType) {
        return repo.findLatestPublishedRule(eventType);
    }

    public Map<String, Object> parseAction(OrchRuleDO rule) {
        if (rule == null || rule.getActionJson() == null) return Map.of("type", "create_run");
        try {
            return mapper.readValue(rule.getActionJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "actionJson 解析失败");
        }
    }

    private RuleView toView(OrchRuleDO row) {
        return new RuleView(row.getId(), row.getName(), row.getEventType(),
                row.getVersion() == null ? 0 : row.getVersion(),
                Boolean.TRUE.equals(row.getPublished()), parseAction(row));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record RuleView(String id, String name, String eventType, int version,
                           boolean published, Map<String, Object> action) {}
}
