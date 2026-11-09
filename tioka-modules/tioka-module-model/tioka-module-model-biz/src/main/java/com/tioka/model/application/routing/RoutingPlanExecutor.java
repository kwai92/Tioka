package com.tioka.model.application.routing;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.routing.RoutingDecider;
import com.tioka.model.api.routing.RoutingMode;
import com.tioka.model.application.gateway.ModelBindingFilter;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.RoutingTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * MH-06（ADR-024「一次选模」）路由计划执行器：只在 Turn 开始时决策；
 * 决策超时/失败走兜底组并写时间线；组内按候选顺序过滤（未启用/冷却中）；
 * preferPreviousInGroup 沿用上一 Turn 模型；FREE_FIRST 失败升主力组或直接失败；
 * 每步（DECIDE/SELECT/REUSE/FAILOVER/FAIL）写路由事件。
 * <p>接入点：{@code DefaultModelGateway.chatTracked} 选模一步的最小侵入前置——
 * 返回 null 时调用方回退既有 {@code resolver.resolve} 路径（FIXED 平铺 / 冻结命中 / 非会话调用 /
 * purpose=DECISION 防递归）。
 */
@Service
public class RoutingPlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(RoutingPlanExecutor.class);

    private final ModelBindingResolver resolver;
    private final ModelEntities entities;
    private final RoutingTelemetry telemetry;
    private final RoutingPlanSpecSource specSource;
    private final RoutingDeciderRegistry registry;
    private final List<ModelBindingFilter> filters;
    private final ExecutorService decidePool;
    private final int defaultTimeoutMs;

    public RoutingPlanExecutor(ModelBindingResolver resolver,
                               ModelEntities entities,
                               RoutingTelemetry telemetry,
                               RoutingPlanSpecSource specSource,
                               RoutingDeciderRegistry registry,
                               List<ModelBindingFilter> filters,
                               @Value("${tioka.model.routing.decide-timeout:1500ms}") java.time.Duration decideTimeout) {
        this.resolver = resolver;
        this.entities = entities;
        this.telemetry = telemetry;
        this.specSource = specSource;
        this.registry = registry;
        this.filters = filters == null ? List.of() : List.copyOf(filters);
        this.defaultTimeoutMs = decideTimeout == null || decideTimeout.isZero() || decideTimeout.isNegative()
                ? RoutingPlanSpecParser.DEFAULT_DECIDE_TIMEOUT_MS : (int) decideTimeout.toMillis();
        this.decidePool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "routing-decider");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Turn 开始选模。返回 null 表示交回既有解析路径（行为不变）；否则返回已冻结的选中结果。
     * 抛出异常时由调用方（gateway）回退既有路径。
     */
    public ModelBindingResolver.Resolution select(ModelCallContext ctx, ModelGateway.ChatRequest request) {
        if (ctx == null || ctx.threadId() == null || ctx.turnId() == null) {
            return null;
        }
        if (ctx.purpose() == com.tioka.model.api.gateway.ModelPurpose.DECISION) {
            return null; // 防递归：决策调用本身不触发路由计划（ADR-024）
        }
        if (entities.getFreeze(ctx.threadId(), ctx.turnId()).isPresent()) {
            return null; // 同 Turn 已冻结 → 既有路径直接命中冻结
        }
        ModelBindingResolver.PlanSelection selection = resolver.planSelectionFor(ctx);
        RoutingPlanSnapshot spec = specSource.load(selection.plan().id()).orElse(null);
        if (spec == null || spec.legacy()) {
            return null; // V1 平铺 spec → 既有 primary/fallback 路径
        }

        String planId = selection.plan().id();
        Integer planVersionNo = selection.planVersionNo();
        String workspaceId = workspaceId(ctx);

        if (spec.mode() == RoutingMode.FIXED) {
            String main = spec.mainGroup();
            return selectFromGroup(ctx, request, selection, spec, main, null, planId, planVersionNo, workspaceId);
        }

        // 1. 决策（超时/失败 → 兜底组 + FAIL 事件）
        long t0 = System.nanoTime();
        String deciderId = spec.decision() == null ? null : spec.decision().deciderId();
        String fallbackGroup = spec.decision() != null && spec.decision().fallbackGroup() != null
                ? spec.decision().fallbackGroup() : spec.mainGroup();
        String group;
        try {
            RoutingDecider decider = registry.get(deciderId);
            if (decider == null) {
                throw new IllegalStateException("未知决策器: " + deciderId);
            }
            RoutingDecider.Decision decision = decideBounded(decider, buildInput(ctx, request, spec),
                    spec.decision() == null ? defaultTimeoutMs : spec.decision().timeoutMs());
            telemetry.recordEvent(workspaceId, ctx.threadId(), ctx.turnId(), planId, planVersionNo,
                    RoutingTelemetry.KIND_DECIDE, spec.mode().name(), deciderId, null, null,
                    decision.confidence(), decision.reason(), elapsedMs(t0));
            group = resolveTargetGroup(spec, decision);
        } catch (Exception e) {
            String reason = e instanceof java.util.concurrent.TimeoutException
                    ? "决策超时(" + (spec.decision() == null ? defaultTimeoutMs : spec.decision().timeoutMs()) + "ms)"
                    : "决策失败: " + e.getMessage();
            telemetry.recordEvent(workspaceId, ctx.threadId(), ctx.turnId(), planId, planVersionNo,
                    RoutingTelemetry.KIND_FAIL, spec.mode().name(), deciderId, fallbackGroup, null,
                    null, reason, elapsedMs(t0));
            log.warn("路由决策失败走兜底组 group={}: {}", fallbackGroup, reason);
            group = fallbackGroup;
        }

        // 2. 目标组选模（FREE_FIRST 从免费组开始）
        if (spec.mode() == RoutingMode.FREE_FIRST) {
            group = spec.freeGroup();
        }
        return selectFromGroup(ctx, request, selection, spec, group, null, planId, planVersionNo, workspaceId);
    }

    /** 组内按候选顺序过滤选模 + 冻结 + SELECT/REUSE 事件；耗尽时 FREE_FIRST/可升级则升主力组，否则 FAIL 抛出。 */
    private ModelBindingResolver.Resolution selectFromGroup(ModelCallContext ctx,
                                                            ModelGateway.ChatRequest request,
                                                            ModelBindingResolver.PlanSelection selection,
                                                            RoutingPlanSnapshot spec,
                                                            String groupName,
                                                            String fromGroup,
                                                            String planId, Integer planVersionNo,
                                                            String workspaceId) {
        List<String> candidates = spec.groupCandidates(groupName);
        String previous = spec.preferPreviousInGroup()
                ? telemetry.latestSelectionBinding(ctx.threadId()).orElse(null) : null;

        // preferPreviousInGroup：上一 Turn 选中 binding 在本组内可用 → 沿用
        if (previous != null && candidates.contains(previous) && passes(ctx, previous)) {
            String freezeReason = "reuse:" + groupName;
            entities.insertFreezeIfAbsent(ctx.threadId(), ctx.turnId(), previous, freezeReason);
            telemetry.recordEvent(workspaceId, ctx.threadId(), ctx.turnId(), planId, planVersionNo,
                    RoutingTelemetry.KIND_REUSE, spec.mode().name(), null, groupName, previous,
                    null, "preferPreviousInGroup 沿用上一模型", null);
            return resolution(selection, previous, candidates, spec, groupName, freezeReason);
        }

        for (String id : candidates) {
            if (!passes(ctx, id)) {
                continue;
            }
            String freezeReason = modeFreezeReason(spec, groupName);
            entities.insertFreezeIfAbsent(ctx.threadId(), ctx.turnId(), id, freezeReason);
            telemetry.recordEvent(workspaceId, ctx.threadId(), ctx.turnId(), planId, planVersionNo,
                    RoutingTelemetry.KIND_SELECT, spec.mode().name(), null, groupName, id,
                    null, fromGroup == null ? "组内选中: " + groupName
                            : fromGroup + " 耗尽升级 " + groupName, null);
            return resolution(selection, id, candidates, spec, groupName, freezeReason);
        }

        // 组耗尽：非主力组（FREE_FIRST 免费组等）→ 升主力组（写 FAILOVER）；主力组耗尽 → 失败
        String main = spec.mainGroup();
        if (main != null && !main.equals(groupName)) {
            telemetry.recordEvent(workspaceId, ctx.threadId(), ctx.turnId(), planId, planVersionNo,
                    RoutingTelemetry.KIND_FAILOVER, spec.mode().name(), null, groupName, null,
                    null, "组 " + groupName + " 无可用候选，升级主力组 " + main, null);
            return selectFromGroup(ctx, request, selection, spec, main, groupName,
                    planId, planVersionNo, workspaceId);
        }
        telemetry.recordEvent(workspaceId, ctx.threadId(), ctx.turnId(), planId, planVersionNo,
                RoutingTelemetry.KIND_FAIL, spec.mode().name(), null, groupName, null,
                null, "组 " + groupName + " 无可用候选且不可升级", null);
        throw new BizException(ErrorCode.MODEL_UNAVAILABLE,
                "路由组无可用候选且不可升级: " + groupName);
    }

    private ModelBindingResolver.Resolution resolution(ModelBindingResolver.PlanSelection selection,
                                                       String bindingId,
                                                       List<String> groupCandidates,
                                                       RoutingPlanSnapshot spec,
                                                       String groupName,
                                                       String reason) {
        // 接力链：本组剩余候选 + 主力组候选（FREE_FIRST 升级语义；其余组为空）
        List<String> chain = new ArrayList<>();
        int idx = groupCandidates.indexOf(bindingId);
        if (idx >= 0) {
            chain.addAll(groupCandidates.subList(idx + 1, groupCandidates.size()));
        }
        String main = spec.mainGroup();
        if (main != null && !main.equals(groupName)) {
            for (String id : spec.groupCandidates(main)) {
                if (!id.equals(bindingId) && !chain.contains(id)) {
                    chain.add(id);
                }
            }
        }
        return resolver.ofBinding(bindingId, chain, reason,
                selection.aliasId(), selection.aliasVersionNo(), selection.planVersionNo());
    }

    private boolean passes(ModelCallContext ctx, String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            return false;
        }
        ModelEntities.ModelBinding b = entities.getModelBinding(bindingId).orElse(null);
        if (b == null || !b.enabled()) {
            return false;
        }
        return filters.stream().allMatch(f -> f.test(ctx, b));
    }

    /** 决策结果 → 目标组（DIFFICULTY / BRANCH 映射；未命中回主力组）。 */
    private static String resolveTargetGroup(RoutingPlanSnapshot spec, RoutingDecider.Decision decision) {
        String main = spec.mainGroup();
        if (spec.mode() == RoutingMode.DIFFICULTY && decision.difficulty() != null) {
            String g = spec.difficultyToGroup().get(decision.difficulty().name());
            return g != null ? g : main;
        }
        if (spec.mode() == RoutingMode.BRANCH && decision.category() != null) {
            String g = spec.categoryToGroup().get(decision.category());
            return g != null ? g : main;
        }
        return main;
    }

    private RoutingDecider.DecisionInput buildInput(ModelCallContext ctx,
                                                    ModelGateway.ChatRequest request,
                                                    RoutingPlanSnapshot spec) {
        String userText = lastUserText(request);
        String previousGroup = null;
        Double previousCompetence = null;
        if (spec.preferPreviousInGroup()) {
            String previousBinding = telemetry.latestSelectionBinding(ctx.threadId()).orElse(null);
            if (previousBinding != null) {
                previousCompetence = telemetry.latestCompetenceScore(previousBinding).orElse(null);
            }
        }
        Map<String, Object> hints = Map.of(
                "principal", ctx.principal(),
                "agentId", ctx.agentId() == null ? "" : ctx.agentId(),
                "purpose", ctx.purpose().name(),
                "toolsRequired", request != null && request.tools() != null && !request.tools().isEmpty(),
                "imageRequired", false);
        return new RoutingDecider.DecisionInput(userText, previousGroup, previousCompetence, hints);
    }

    /** 决策限时执行；超时取消并抛 TimeoutException（执行侧走兜底组）。 */
    private RoutingDecider.Decision decideBounded(RoutingDecider decider,
                                                  RoutingDecider.DecisionInput input,
                                                  int timeoutMs) throws Exception {
        int bound = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        Future<RoutingDecider.Decision> future = decidePool.submit(() -> decider.decide(input));
        try {
            return future.get(bound, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private static String lastUserText(ModelGateway.ChatRequest request) {
        if (request == null || request.messages() == null) {
            return "";
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Map<String, Object> m = request.messages().get(i);
            if (m != null && "user".equals(String.valueOf(m.get("role")))) {
                Object c = m.get("content");
                return c == null ? "" : String.valueOf(c);
            }
        }
        return "";
    }

    private static String modeFreezeReason(RoutingPlanSnapshot spec, String groupName) {
        return spec.mode().name() + ":" + groupName;
    }

    private static String workspaceId(ModelCallContext ctx) {
        Principal p = ctx.principal();
        return p == null ? null : p.workspaceId();
    }

    private static int elapsedMs(long t0Nanos) {
        return (int) Math.max(0L, (System.nanoTime() - t0Nanos) / 1_000_000L);
    }
}
