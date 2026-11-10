package com.tioka.model.application.gateway;

import com.tioka.framework.error.ErrorCode;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.label.TurnLabelLookup;
import com.tioka.model.application.routing.PlanLabelGate;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.RoutingTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * MH-06 binding 级数据标签过滤（DAT-03 / ModelBindingFilter javadoc 预留位）。
 *
 * <p>Turn 当前标签超出 MODEL_PLAN 去向许可 → 剔除该 binding 并记
 * {@code DATA_LABEL_EXCEEDED(40332)} 事件（路由时间线 kind=FAIL）。
 * plan 级前置过滤在 {@link PlanLabelGate}（RoutingPlanExecutor.select）；本过滤器兜底
 * 既有 resolver.resolve 路径。SinkGuard / lookup 缺失（模块单测装配）恒放行。
 */
@Component
public class LabelModelPlanFilter implements ModelBindingFilter {

    private static final Logger log = LoggerFactory.getLogger(LabelModelPlanFilter.class);

    private final PlanLabelGate gate;
    private final ObjectProvider<SinkGuard> sinkGuard;
    private final RoutingTelemetry telemetry;

    public LabelModelPlanFilter(PlanLabelGate gate,
                                ObjectProvider<SinkGuard> sinkGuard,
                                RoutingTelemetry telemetry) {
        this.gate = gate;
        this.sinkGuard = sinkGuard;
        this.telemetry = telemetry;
    }

    @Override
    public boolean test(ModelCallContext ctx, ModelEntities.ModelBinding binding) {
        if (ctx == null || ctx.threadId() == null || binding == null) {
            return true;
        }
        SinkGuard guard = sinkGuard.getIfAvailable();
        if (guard == null) {
            return true;
        }
        var label = gate.currentLabel(ctx);
        SinkCheckResult r = guard.check(SinkKind.MODEL_PLAN, "*", label, null);
        if (r.verdict() == SinkCheckResult.SinkVerdict.PASS) {
            return true;
        }
        log.info("binding 因数据标签剔除 binding={} label={}", binding.id(), label);
        if (telemetry != null) {
            try {
                telemetry.recordEvent(workspaceId(ctx), ctx.threadId(), ctx.turnId(), null, null,
                        RoutingTelemetry.KIND_FAIL, "LABEL", null, null, binding.id(),
                        null, ErrorCode.DATA_LABEL_EXCEEDED.getCode() + ": DATA_LABEL_EXCEEDED",
                        null);
            } catch (RuntimeException e) {
                log.warn("DATA_LABEL_EXCEEDED 事件记录失败: {}", e.toString());
            }
        }
        return false;
    }

    private static String workspaceId(ModelCallContext ctx) {
        var p = ctx.principal();
        return p == null ? null : p.workspaceId();
    }
}
