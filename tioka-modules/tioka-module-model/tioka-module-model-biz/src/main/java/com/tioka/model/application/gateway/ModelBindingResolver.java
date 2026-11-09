package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.ModelAlias;
import com.tioka.model.domain.ModelEntities.ModelBinding;
import com.tioka.model.domain.ModelEntities.Provider;
import com.tioka.model.domain.ModelEntities.RoutingPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * MH-04 / MH-01：选模与轮次冻结。MH-08 起默认方案经 sys.* 系统别名解析
 * （ADR-041 §1：M1 的 model_default_plan 已迁移为 sys.chat / sys.flow / sys.embedding /
 * sys.rerank / sys.decision），别名携带当前生效版本号供调用记录切片。
 */
@Component
public class ModelBindingResolver {
    private final ModelEntities entities;
    private final List<ModelBindingFilter> filters;

    public ModelBindingResolver(ModelEntities entities, List<ModelBindingFilter> filters) {
        this.entities = entities;
        this.filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public Resolution resolve(ModelCallContext ctx) {
        return resolve(ctx, null);
    }

    /** {@code forcedBindingId} 非空时跳过方案选择（嵌入/rerank 显式绑定）。 */
    public Resolution resolve(ModelCallContext ctx, String forcedBindingId) {
        if (forcedBindingId != null && !forcedBindingId.isBlank()) {
            return ofBinding(forcedBindingId, List.of(), "forced", null, null, null);
        }

        PlanSelection selection = resolvePlan(ctx);
        RoutingPlan plan = selection.plan();
        List<String> chain = fallbackChain(plan);

        // 嵌入 / rerank：turnId=null，不冻结
        if (ctx.turnId() != null && ctx.threadId() != null) {
            Optional<String> frozen = entities.getFreeze(ctx.threadId(), ctx.turnId());
            if (frozen.isPresent()) {
                return ofBinding(frozen.get(), chainAfter(frozen.get(), chain), "frozen",
                        selection.aliasId(), selection.aliasVersionNo(), selection.planVersionNo());
            }
        }

        String primary = switch (normalizeType(plan.type())) {
            case "HARD" -> require(plan.hardBindingId() != null ? plan.hardBindingId() : plan.primaryBindingId(),
                    "HARD 方案缺少 hardBindingId");
            case "PRIMARY_FALLBACK", "FIXED", "PRIMARY" -> require(plan.primaryBindingId(), "方案缺少 primaryBindingId");
            case "DIFFICULTY" -> throw NotImplemented.of("routing-plan-DIFFICULTY", "ADR-024");
            default -> require(plan.primaryBindingId(), "方案缺少 primaryBindingId");
        };
        primary = firstPassing(ctx, primary, chain);

        if (ctx.turnId() != null && ctx.threadId() != null) {
            String chosen = entities.insertFreezeIfAbsent(
                    ctx.threadId(), ctx.turnId(), primary, plan.type());
            String reason = chosen.equals(primary) ? plan.type() : "frozen";
            return ofBinding(chosen, chainAfter(chosen, chain), reason,
                    selection.aliasId(), selection.aliasVersionNo(), selection.planVersionNo());
        }
        return ofBinding(primary, chainAfter(primary, chain), plan.type(),
                selection.aliasId(), selection.aliasVersionNo(), selection.planVersionNo());
    }

    private String firstPassing(ModelCallContext ctx, String primary, List<String> chain) {
        List<String> candidates = new ArrayList<>();
        candidates.add(primary);
        candidates.addAll(chain);
        for (String id : candidates) {
            if (id == null || id.isBlank()) {
                continue;
            }
            ModelBinding b = entities.getModelBinding(id)
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

    /** MH-08：解析顺序 显式 planId → sys.* 别名（按 purpose）→ 已发布方案 → 任意启用 binding。 */
    private PlanSelection resolvePlan(ModelCallContext ctx) {
        if (ctx.routingPlanId() != null && !ctx.routingPlanId().isBlank()) {
            RoutingPlan plan = entities.getPlan(ctx.routingPlanId())
                    .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "路由计划未发布或不存在"));
            return new PlanSelection(plan, null, null, plan.currentVersionNo());
        }
        Optional<ModelAlias> sysAlias = entities.findAliasByName(
                ModelHubSpecs.sysAliasName(ctx.purpose().name()));
        if (sysAlias.isPresent()) {
            ModelAlias alias = sysAlias.get();
            if (ModelHubSpecs.TARGET_ROUTING_PLAN.equals(alias.targetType())
                    && alias.targetId() != null && !alias.targetId().isBlank()) {
                RoutingPlan plan = entities.getPlan(alias.targetId())
                        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "别名指向的路由计划不存在"));
                return new PlanSelection(plan, alias.id(), alias.currentVersionNo(), plan.currentVersionNo());
            }
            if (ModelHubSpecs.TARGET_BINDING.equals(alias.targetType())
                    && alias.targetId() != null && !alias.targetId().isBlank()) {
                return new PlanSelection(syntheticHard(alias.targetId()),
                        alias.id(), alias.currentVersionNo(), null);
            }
        }
        Optional<RoutingPlan> published = entities.plans().stream().filter(RoutingPlan::published).findFirst();
        if (published.isPresent()) {
            RoutingPlan plan = published.get();
            return new PlanSelection(plan, null, null, plan.currentVersionNo());
        }
        // 嵌入等无会话调用：尚无发布方案时用任意启用 binding 合成 HARD（MH-04 过渡）
        return entities.firstEnabledBinding()
                .map(b -> new PlanSelection(syntheticHard(b.id()), null, null, null))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "未指定 routingPlanId 且无 purpose=" + ctx.purpose() + " 系统别名/可用 binding"));
    }

    private static RoutingPlan syntheticHard(String bindingId) {
        return new RoutingPlan("default-hard", "default", "HARD", bindingId, null, bindingId,
                true, null, null);
    }

    /** MH-02：按指定 binding 构造 Resolution（故障接力切到链上下一个）。 */
    public Resolution ofBinding(String bindingId, List<String> chain, String reason,
                                String aliasId, Integer aliasVersionNo, Integer planVersionNo) {
        ModelBinding b = entities.getModelBinding(bindingId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "binding 丢失"));
        Provider p = entities.getProvider(b.providerId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "provider 丢失"));
        String modelRef = b.logicalModelId() + "@" + b.modelName();
        return new Resolution(bindingId, modelRef, p.baseUrl(), p.apiKeyRef(), b.modelName(),
                chain == null ? List.of() : List.copyOf(chain), reason, b,
                aliasId, aliasVersionNo, planVersionNo);
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

    /** 别名解析结果：plan + 来源别名（含版本）。 */
    private record PlanSelection(RoutingPlan plan, String aliasId,
                                 Integer aliasVersionNo, Integer planVersionNo) {}

    /**
     * @param binding        已解析的绑定快照，供后续限流/计价使用
     * @param aliasVersionNo 来源别名当前生效版本（MH-08；无别名来源时为 null）
     * @param planVersionNo  路由计划当前生效版本（合成 HARD 时为 null）
     */
    public record Resolution(String bindingId, String modelRef, String baseUrl, String apiKeyRef,
                             String modelName, List<String> fallbackChain, String reason,
                             ModelBinding binding,
                             String aliasId, Integer aliasVersionNo, Integer planVersionNo) {}
}
