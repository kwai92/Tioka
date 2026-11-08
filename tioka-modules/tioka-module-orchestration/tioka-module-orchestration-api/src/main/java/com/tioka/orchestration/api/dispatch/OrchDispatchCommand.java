package com.tioka.orchestration.api.dispatch;

import java.util.Map;

/**
 * 派工命令（ORC-03）。业务入参 ≥3，使用本对象（48-CS）。
 *
 * @param runId          编排 Run ID
 * @param workItemId     工单 ID
 * @param ruleVersionId  规则版本 ID
 * @param actionType     动作类型
 * @param action         动作配置（agentVersionId / flowPublicationId 等）
 * @param eventAttrs     事件可信属性
 * @param eventBody      事件正文
 */
public record OrchDispatchCommand(
        String runId,
        String workItemId,
        String ruleVersionId,
        OrchActionType actionType,
        Map<String, Object> action,
        Map<String, String> eventAttrs,
        Map<String, Object> eventBody) {

    public OrchDispatchCommand {
        action = action == null ? Map.of() : Map.copyOf(action);
        eventAttrs = eventAttrs == null ? Map.of() : Map.copyOf(eventAttrs);
        eventBody = eventBody == null ? Map.of() : Map.copyOf(eventBody);
    }
}
