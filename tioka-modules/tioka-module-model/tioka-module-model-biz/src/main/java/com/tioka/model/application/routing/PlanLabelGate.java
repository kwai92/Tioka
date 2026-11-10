package com.tioka.model.application.routing;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.label.TurnLabelLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * DAT-03 plan 级去向许可闸（M3-02「按标签选模」）：
 * Turn 当前标签 &gt; MODEL_PLAN 去向许可 → 剔除该计划；全剔除 → NO_ELIGIBLE_MODEL(42210)。
 *
 * <p>M3 口径：MODEL_PLAN 的 REDACT_THEN_* 只在 PASS 之外一律不可选
 * （提示词假名化 / 审批处理在 M4 护栏管线，登记偏差）；非会话调用 / lookup 缺失（模块单测）
 * 不参与标签过滤。
 */
@Component
public class PlanLabelGate {

    private static final Logger log = LoggerFactory.getLogger(PlanLabelGate.class);

    private final ObjectProvider<TurnLabelLookup> labelLookup;
    private final ObjectProvider<SinkGuard> sinkGuard;

    public PlanLabelGate(ObjectProvider<TurnLabelLookup> labelLookup,
                         ObjectProvider<SinkGuard> sinkGuard) {
        this.labelLookup = labelLookup;
        this.sinkGuard = sinkGuard;
    }

    /**
     * @return 当前生效标签（无 lookup / 未知 thread → PUBLIC）；lookup 存在但 SinkGuard
     *         缺失时返回 PUBLIC（不过滤）。
     */
    public DataLabel currentLabel(ModelCallContext ctx) {
        if (ctx == null || ctx.threadId() == null || labelLookup == null) {
            return DataLabel.PUBLIC;
        }
        TurnLabelLookup lookup = labelLookup.getIfAvailable();
        if (lookup == null) {
            return DataLabel.PUBLIC;
        }
        return lookup.labelOf(ctx.threadId()).orElse(DataLabel.PUBLIC);
    }

    /** plan 是否获准（label ≤ 许可）。SinkGuard 缺失 → 放行（不过滤）。 */
    public boolean allowed(ModelCallContext ctx, String planId, DataLabel label) {
        SinkGuard guard = sinkGuard == null ? null : sinkGuard.getIfAvailable();
        if (guard == null) {
            return true;
        }
        SinkCheckResult r = guard.check(SinkKind.MODEL_PLAN, planId, label, null);
        if (r.verdict() == SinkCheckResult.SinkVerdict.PASS) {
            return true;
        }
        log.info("MODEL_PLAN 去向不获准 plan={} label={} verdict={}", planId, label, r.verdict());
        return false;
    }

    /** plan 级前置过滤（RoutingPlanExecutor.select 前置）。 */
    public void assertPlanAllowed(ModelCallContext ctx, String planId, DataLabel label) {
        if (allowed(ctx, planId, label)) {
            return;
        }
        throw new BizException(ErrorCode.NO_ELIGIBLE_MODEL,
                "当前对话含 " + label + " 数据，没有获准的模型");
    }
}
