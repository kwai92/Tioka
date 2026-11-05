package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.model.api.FailoverEvent;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRouterService implements ModelRouterApi {
    private final ApplicationEventPublisher events;
    private final ModelEntities entities;
    private final Map<String, RouteView> lastRoutes = new ConcurrentHashMap<>();

    public ModelRouterService(ApplicationEventPublisher events, ModelEntities entities) {
        this.events = events;
        this.entities = entities;
    }

    @Override
    public ModelSelection select(String sessionId, String turnId, String routingPlanId, String userText) {
        String tid = turnId == null || turnId.isBlank() ? sessionId : turnId;
        var frozen = entities.getFreeze(sessionId, tid);
        if (frozen.isPresent()) {
            ModelSelection sel = resolveBinding(frozen.get(), "frozen");
            remember(sessionId, sel);
            return sel;
        }
        RoutingPlan plan = entities.getPlan(routingPlanId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在"));
        if (!plan.published()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在");
        }
        if ("DIFFICULTY".equalsIgnoreCase(plan.type())) {
            throw NotImplemented.of("routing-plan-DIFFICULTY", "ADR-024");
        }
        String chosen = plan.primaryBindingId();
        String reason = "primary";
        entities.putFreeze(sessionId, tid, chosen);
        ModelSelection sel = resolveBinding(chosen, reason);
        remember(sessionId, sel);
        return sel;
    }

    @Override
    public ModelSelection failover(String sessionId, String turnId, String routingPlanId, String reason) {
        String tid = turnId == null || turnId.isBlank() ? sessionId : turnId;
        RoutingPlan plan = entities.getPlan(routingPlanId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在"));
        if (!plan.published()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在");
        }
        if (plan.fallbackBindingId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未配置 fallbackBinding");
        }
        entities.putFreeze(sessionId, tid, plan.fallbackBindingId());
        String routeReason = "failover:" + (reason == null ? "unspecified" : reason);
        ModelSelection sel = resolveBinding(plan.fallbackBindingId(), routeReason);
        remember(sessionId, sel);
        events.publishEvent(new FailoverEvent(sessionId, sel.modelRef(), reason == null ? "failover" : reason));
        return sel;
    }

    @Override
    public Optional<RouteView> lastRoute(String sessionId) {
        return Optional.ofNullable(lastRoutes.get(sessionId));
    }

    private void remember(String sessionId, ModelSelection sel) {
        lastRoutes.put(sessionId, new RouteView(sessionId, sel.bindingId(), sel.modelRef(), sel.reason()));
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
