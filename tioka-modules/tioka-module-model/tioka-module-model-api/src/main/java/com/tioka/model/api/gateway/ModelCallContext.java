package com.tioka.model.api.gateway;

import com.tioka.framework.security.Principal;

import java.util.Objects;

/**
 * 一次模型调用的归属。同一 {@code (sessionId, turnId)} 内选模冻结（ADR-017）。
 *
 * @param sessionId     Runtime thread ID；非会话调用（如知识入库）为 null
 * @param turnId        Turn ID；非会话调用为 null
 * @param agentId       配额与用量归集的 Agent；可空
 * @param routingPlanId 路由方案；null 表示用 Agent 或空间默认方案
 */
public record ModelCallContext(Principal principal, String sessionId, String turnId,
                               String agentId, String routingPlanId, ModelPurpose purpose) {
    public ModelCallContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(purpose, "purpose");
    }
}
