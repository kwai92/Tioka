package com.tioka.model.api;

import java.util.Optional;

public interface ModelRouterApi {
    ModelSelection select(String sessionId, String routingPlanId);

    ModelSelection select(String sessionId, String routingPlanId, String userText);

    ModelSelection failover(String sessionId, String routingPlanId, String reason);

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
