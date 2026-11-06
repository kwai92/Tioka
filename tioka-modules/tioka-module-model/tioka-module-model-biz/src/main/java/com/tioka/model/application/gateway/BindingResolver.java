package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.Binding;
import com.tioka.model.domain.ModelEntities.Provider;
import com.tioka.model.domain.ModelEntities.RoutingPlan;
import com.tioka.model.infrastructure.mapper.ModelDefaultPlanMapper;
import com.tioka.model.infrastructure.persistence.ModelDefaultPlanDO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * MH-04 / MH-01：选模与轮次冻结。MH-04 先用现有方案选择；冻结写入供后续步复用。
 */
@Component
public class BindingResolver {
    private final ModelEntities entities;
    private final ModelDefaultPlanMapper defaultPlans;
    private final List<BindingFilter> filters;

    public BindingResolver(ModelEntities entities,
                           ModelDefaultPlanMapper defaultPlans,
                           List<BindingFilter> filters) {
        this.entities = entities;
        this.defaultPlans = defaultPlans;
        this.filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public Resolution resolve(ModelCallContext ctx) {
        return resolve(ctx, null);
    }

    /** {@code forcedBindingId} 非空时跳过方案选择（嵌入/rerank 显式绑定）。 */
    public Resolution resolve(ModelCallContext ctx, String forcedBindingId) {
        if (forcedBindingId != null && !forcedBindingId.isBlank()) {
            return ofBinding(forcedBindingId, List.of(), "forced");
        }
        if (ctx.turnId() != null && ctx.sessionId() != null) {
            Optional<String> frozen = entities.getFreeze(ctx.sessionId(), ctx.turnId());
            if (frozen.isPresent()) {
                return ofBinding(frozen.get(), List.of(), "frozen");
            }
        }

        RoutingPlan plan = resolvePlan(ctx);
        String primary = switch (normalizeType(plan.type())) {
            case "HARD" -> require(plan.hardBindingId() != null ? plan.hardBindingId() : plan.primaryBindingId(),
                    "HARD 方案缺少 hardBindingId");
            case "PRIMARY_FALLBACK", "FIXED", "PRIMARY" -> require(plan.primaryBindingId(), "方案缺少 primaryBindingId");
            case "DIFFICULTY" -> throw NotImplemented.of("routing-plan-DIFFICULTY", "ADR-024");
            default -> require(plan.primaryBindingId(), "方案缺少 primaryBindingId");
        };
        List<String> chain = fallbackChain(plan);
        primary = firstPassing(ctx, primary, chain);

        if (ctx.turnId() != null && ctx.sessionId() != null) {
            entities.putFreeze(ctx.sessionId(), ctx.turnId(), primary);
        }
        return ofBinding(primary, chainAfter(primary, chain), plan.type());
    }

    private String firstPassing(ModelCallContext ctx, String primary, List<String> chain) {
        List<String> candidates = new ArrayList<>();
        candidates.add(primary);
        candidates.addAll(chain);
        for (String id : candidates) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Binding b = entities.getBinding(id)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "binding 丢失: " + id));
            if (!b.enabled()) {
                continue;
            }
            if (filters.stream().allMatch(f -> f.test(ctx, b))) {
                return id;
            }
        }
        throw new BizException(ErrorCode.DEPENDENCY, "无可用 binding（过滤后为空）");
    }

    private RoutingPlan resolvePlan(ModelCallContext ctx) {
        if (ctx.routingPlanId() != null && !ctx.routingPlanId().isBlank()) {
            return entities.getPlan(ctx.routingPlanId())
                    .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在"));
        }
        ModelDefaultPlanDO def = defaultPlans.selectById(ctx.purpose().name());
        if (def != null && def.getRoutingPlanId() != null && !def.getRoutingPlanId().isBlank()) {
            return entities.getPlan(def.getRoutingPlanId())
                    .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "默认路由计划不存在"));
        }
        if (def != null && def.getBindingId() != null && !def.getBindingId().isBlank()) {
            return syntheticHard(def.getBindingId());
        }
        Optional<RoutingPlan> published = entities.plans().stream().filter(RoutingPlan::published).findFirst();
        if (published.isPresent()) {
            return published.get();
        }
        // 嵌入等无会话调用：尚无发布方案时用任意启用 binding 合成 HARD（MH-04 过渡）
        return entities.firstEnabledBinding()
                .map(b -> syntheticHard(b.id()))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "未指定 routingPlanId 且无 purpose=" + ctx.purpose() + " 默认方案/可用 binding"));
    }

    private static RoutingPlan syntheticHard(String bindingId) {
        return new RoutingPlan("default-hard", "default", "HARD", bindingId, null, bindingId,
                true, null, null);
    }

    private Resolution ofBinding(String bindingId, List<String> chain, String reason) {
        Binding b = entities.getBinding(bindingId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "binding 丢失"));
        Provider p = entities.getProvider(b.providerId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "provider 丢失"));
        String modelRef = b.logicalModelId() + "@" + b.modelName();
        return new Resolution(bindingId, modelRef, p.baseUrl(), p.apiKeyRef(), b.modelName(),
                chain, reason, b);
    }

    private static List<String> fallbackChain(RoutingPlan plan) {
        if (plan.fallbackChain() != null && !plan.fallbackChain().isBlank()) {
            return Arrays.stream(plan.fallbackChain().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        if (plan.fallbackBindingId() != null && !plan.fallbackBindingId().isBlank()) {
            return List.of(plan.fallbackBindingId());
        }
        return List.of();
    }

    private static List<String> chainAfter(String primary, List<String> chain) {
        int idx = chain.indexOf(primary);
        if (idx < 0) {
            return List.copyOf(chain);
        }
        return List.copyOf(chain.subList(idx + 1, chain.size()));
    }

    private static String normalizeType(String type) {
        return type == null ? "PRIMARY_FALLBACK" : type.trim().toUpperCase();
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, message);
        }
        return value;
    }

    /** @param binding 已解析的绑定快照，供后续限流/计价使用 */
    public record Resolution(String bindingId, String modelRef, String baseUrl, String apiKeyRef,
                             String modelName, List<String> fallbackChain, String reason,
                             Binding binding) {}
}
