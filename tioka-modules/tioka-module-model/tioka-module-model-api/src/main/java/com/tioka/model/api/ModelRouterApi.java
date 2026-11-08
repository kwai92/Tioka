package com.tioka.model.api;

import java.util.List;

/**
 * 模型路由调试面。选模已收口到 {@code ModelGateway}/{@code ModelBindingResolver}（MH-01）；
 * 本 API 仅保留故障接力调试与冻结查询。
 */
public interface ModelRouterApi {

    ModelSelection failover(String threadId, String turnId, String routingPlanId, String reason);

    default ModelSelection failover(String threadId, String routingPlanId, String reason) {
        return failover(threadId, threadId, routingPlanId, reason);
    }

    /** 查询会话下全部轮次冻结（替代内存 lastRoutes）。 */
    List<FreezeView> listFreezes(String threadId);

    record ModelSelection(String modelRef, String providerId, String bindingId, String baseUrl,
                          String apiKeyRef, String modelName, String reason) {
        public ModelSelection(String modelRef, String providerId, String bindingId, String baseUrl,
                              String apiKeyRef, String modelName) {
            this(modelRef, providerId, bindingId, baseUrl, apiKeyRef, modelName, null);
        }
    }

    record FreezeView(String threadId, String turnId, String bindingId, String reason, int failoverCount) {}
}
