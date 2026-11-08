package com.tioka.orchestration.api.dispatch;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * {@link OrchActionType#FLOW} 占位：M2 拒绝派工；M3 FLW-06 接通时替换本类。
 */
public final class UnsupportedFlowActionDispatcher implements OrchActionDispatcher {

    @Override
    public OrchActionType type() {
        return OrchActionType.FLOW;
    }

    @Override
    public void dispatch(OrchDispatchCommand command) {
        Span span = GlobalOpenTelemetry.getTracer("tioka").spanBuilder("orch.dispatch").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (command != null) {
                if (command.ruleVersionId() != null) {
                    span.setAttribute("tioka.rule_id", command.ruleVersionId());
                }
                if (command.runId() != null) {
                    span.setAttribute("tioka.run_id", command.runId());
                }
            }
            throw new UnsupportedOperationException("flow 动作在 M3 FLW-06 接通前不可用（约束 F23）");
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
