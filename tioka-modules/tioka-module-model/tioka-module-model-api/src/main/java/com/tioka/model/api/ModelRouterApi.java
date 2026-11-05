package com.tioka.model.api;

import java.util.Optional;

public interface ModelRouterApi {
    /** 兼容旧调用：turnId 取 sessionId。 */
    default ModelSelection select(String sessionId, String routingPlanId) {
        return select(sessionId, sessionId, routingPlanId, null);
    }

    /** 兼容旧调用：turnId 取 sessionId。 */
    default ModelSelection select(String sessionId, String routingPlanId, String userText) {
        return select(sessionId, sessionId, routingPlanId, userText);
    }

    /** 按 Turn 冻结选模（ADR-017 / model_turn_freeze）。 */
    ModelSelection select(String sessionId, String turnId, String routingPlanId, String userText);

    ModelSelection failover(String sessionId, String turnId, String routingPlanId, String reason);

    default ModelSelection failover(String sessionId, String routingPlanId, String reason) {
        return failover(sessionId, sessionId, routingPlanId, reason);
    }

    /** 会话最近一次选模原因（含冻结复用）。 */
    default Optional<RouteView> lastRoute(String sessionId) {
        return Optional.empty();
    }

    record ModelSelection(String modelRef, String providerId, String bindingId, String baseUrl,
                          String apiKeyRef, String modelName, String reason) {
        public ModelSelection(String modelRef, String providerId, String bindingId, String baseUrl,
                              String apiKeyRef, String modelName) {
            this(modelRef, providerId, bindingId, baseUrl, apiKeyRef, modelName, null);
        }
    }

    record RouteView(String sessionId, String bindingId, String modelRef, String reason) {}
}
