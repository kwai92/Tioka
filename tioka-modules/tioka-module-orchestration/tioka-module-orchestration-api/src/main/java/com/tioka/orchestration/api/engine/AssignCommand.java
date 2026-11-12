package com.tioka.orchestration.api.engine;

import java.util.Map;

/**
 * ACCEPT 后归单命令（业务入参 ≥3，48-CS）。
 *
 * @param eventId            事件 ID
 * @param ruleId             规则 ID
 * @param ruleVersionId      规则版本 ID
 * @param subjectKey         归单键
 * @param concurrency        并发模式
 * @param strength           强度
 * @param via                Mandate 来由
 * @param serviceAccountId   执行服务账号
 * @param requesterSource    发起来源，可空
 * @param requesterExternalId 外部发起人 ID，可空
 * @param requesterUserId    映射后的用户 ID，可空
 * @param scopeJson          Mandate 范围快照
 * @param eventAttrs         事件可信属性
 * @param eventBody          事件正文（进 Run 输入）
 * @param agentVersionId     派工目标 Agent 版本（TRU-03 / ADR-029 第 36 条），可空表示非 Agent 动作
 */
public record AssignCommand(
        String eventId,
        String ruleId,
        String ruleVersionId,
        String subjectKey,
        ConcurrencyMode concurrency,
        OrchApi.Strength strength,
        MandateVia via,
        String serviceAccountId,
        String requesterSource,
        String requesterExternalId,
        String requesterUserId,
        Map<String, Object> scopeJson,
        Map<String, String> eventAttrs,
        Map<String, Object> eventBody,
        String agentVersionId) {

    /** 兼容十四参构造（TRU-03 前调用点；无 Agent 动作语义）。 */
    public AssignCommand(String eventId, String ruleId, String ruleVersionId, String subjectKey,
                         ConcurrencyMode concurrency, OrchApi.Strength strength, MandateVia via,
                         String serviceAccountId, String requesterSource, String requesterExternalId,
                         String requesterUserId, Map<String, Object> scopeJson,
                         Map<String, String> eventAttrs, Map<String, Object> eventBody) {
        this(eventId, ruleId, ruleVersionId, subjectKey, concurrency, strength, via,
                serviceAccountId, requesterSource, requesterExternalId, requesterUserId,
                scopeJson, eventAttrs, eventBody, null);
    }

    public AssignCommand {
        scopeJson = scopeJson == null ? Map.of() : Map.copyOf(scopeJson);
        eventAttrs = eventAttrs == null ? Map.of() : Map.copyOf(eventAttrs);
        eventBody = eventBody == null ? Map.of() : Map.copyOf(eventBody);
    }
}
