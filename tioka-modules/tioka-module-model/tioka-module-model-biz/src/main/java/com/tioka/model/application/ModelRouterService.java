package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.FailoverEvent;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.Binding;
import com.tioka.model.domain.ModelEntities.Provider;
import com.tioka.model.domain.ModelEntities.RoutingPlan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MH-01：去掉内存 {@code lastRoutes} 与 {@code select}；冻结查询走 {@code model_turn_freeze}。
 * 手工 failover 仍更新冻结（MH-02 自动故障接力接入前保留）。
 */
@Service
public class ModelRouterService implements ModelRouterApi {
    private final ApplicationEventPublisher events;
    private final ModelEntities entities;

    public ModelRouterService(ApplicationEventPublisher events, ModelEntities entities) {
        this.events = events;
        this.entities = entities;
    }

    @Override
    public ModelSelection failover(String sessionId, String turnId, String routingPlanId, String reason) {
        String tid = turnId == null || turnId.isBlank() ? sessionId : turnId;
        RoutingPlan plan = entities.getPlan(routingPlanId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在"));
        if (!plan.published()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在");
        }
        String next = plan.fallbackBindingId();
        if ((next == null || next.isBlank()) && plan.fallbackChain() != null && !plan.fallbackChain().isBlank()) {
            next = plan.fallbackChain().split(",")[0].trim();
        }
        if (next == null || next.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未配置 fallbackBinding");
        }
        entities.putFreeze(sessionId, tid, next);
        String routeReason = "failover:" + (reason == null ? "unspecified" : reason);
        ModelSelection sel = resolveBinding(next, routeReason);
        events.publishEvent(new FailoverEvent(sessionId, sel.modelRef(), reason == null ? "failover" : reason));
        return sel;
    }

    @Override
    public List<FreezeView> listFreezes(String sessionId) {
        return entities.listFreezes(sessionId).stream()
                .map(f -> new FreezeView(f.sessionId(), f.turnId(), f.bindingId(), f.reason(), f.failoverCount()))
                .toList();
    }

    private ModelSelection resolveBinding(String bindingId, String reason) {
        Binding b = entities.getBinding(bindingId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "binding 丢失"));
        Provider p = entities.getProvider(b.providerId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "provider 丢失"));
        return new ModelSelection(b.logicalModelId() + "@" + b.modelName(), p.id(), b.id(),
                p.baseUrl(), p.apiKeyRef(), b.modelName(), reason);
    }
}
