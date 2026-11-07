package com.tioka.orchestration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.ResourceGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 发布时校验动作：M2 仅开放 agent / admission；flow 拒绝（F23，不引用 {@code OrchActionType.FLOW} 字段）。
 */
@Component
public class RuleActionValidator {
    private final ObjectMapper mapper;
    private final AgentSnapshotApi agentSnapshots;
    private final ResourceGuard resourceGuard;

    public RuleActionValidator(ObjectMapper mapper,
                               AgentSnapshotApi agentSnapshots,
                               ResourceGuard resourceGuard) {
        this.mapper = mapper;
        this.agentSnapshots = agentSnapshots;
        this.resourceGuard = resourceGuard;
    }

    public void validate(String actionsJson, String serviceAccountId) {
        if (serviceAccountId == null || serviceAccountId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "serviceAccountId 必填");
        }
        List<Map<String, Object>> actions = parse(actionsJson);
        if (actions.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "actions 不能为空");
        }
        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> action = actions.get(i);
            String type = String.valueOf(action.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
            if ("flow".equals(type)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "flow 动作未开放（约束 F23 / ADR-029 第 65 条）");
            }
            if (!"agent".equals(type) && !"admission".equals(type)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持的动作类型: " + type);
            }
            if ("admission".equals(type) && i != 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "admission 只能出现在第一个动作");
            }
            String agentVersionId = stringVal(action.get("agentVersionId"));
            if (agentVersionId == null || agentVersionId.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "agentVersionId 必填");
            }
            AgentSnapshotApi.AgentSnapshot snap = agentSnapshots.get(agentVersionId);
            resourceGuard.check("agent", snap.agentId(), AccessLevel.USE);
        }
    }

    private List<Map<String, Object>> parse(String actionsJson) {
        try {
            return mapper.readValue(actionsJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "actions_json 解析失败");
        }
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
