package com.tioka.model.application.routing;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.label.TurnLabelLookup;
import com.tioka.model.application.gateway.LabelModelPlanFilter;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.RoutingTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按标签选模离线测试（DAT-03）：三类用例——plan 级闸拒绝（NO_ELIGIBLE_MODEL 42210）、
 * PUBLIC 放行、binding 级剔除并记录 DATA_LABEL_EXCEEDED(40332)。
 * lookup / SinkGuard / telemetry 用 Mockito（离线优先惯例）。
 */
class LabelRoutingIT {

    private static ModelCallContext ctx(String threadId) {
        return new ModelCallContext(new Principal("u_1", "ws_1", PrincipalType.USER,
                null, null, "password"), threadId, "turn_1", "agt_1", null, ModelPurpose.CHAT);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    /** SinkGuard 假实现：MODEL_PLAN 通道 maxLabel=CONFIDENTIAL，超出即 DENY。 */
    private static SinkGuard modelPlanGuard() {
        return (kind, sinkRef, label, payload) -> {
            if (kind == SinkKind.MODEL_PLAN && label != null
                    && !label.atMost(DataLabel.CONFIDENTIAL)) {
                return SinkCheckResult.deny("label 超出 MODEL_PLAN 许可");
            }
            return SinkCheckResult.pass();
        };
    }

    // ---------- 用例 1：plan 级闸拒绝 ----------
    @Test
    void planGateRejectsWhenLabelExceeds() {
        TurnLabelLookup lookup = threadId ->
                "t_hot".equals(threadId) ? Optional.of(DataLabel.SECRET) : Optional.empty();
        PlanLabelGate gate = new PlanLabelGate(providerOf(lookup), providerOf(modelPlanGuard()));

        // currentLabel 读 runtime thread 标签
        assertEquals(DataLabel.SECRET, gate.currentLabel(ctx("t_hot")));
        assertEquals(DataLabel.PUBLIC, gate.currentLabel(ctx("t_unknown")));
        assertEquals(DataLabel.PUBLIC, gate.currentLabel(null));
        assertEquals(DataLabel.PUBLIC, gate.currentLabel(ctx(null)));

        // SECRET > CONFIDENTIAL → NO_ELIGIBLE_MODEL(42210)
        BizException e = assertThrows(BizException.class,
                () -> gate.assertPlanAllowed(ctx("t_hot"), "plan_1", DataLabel.SECRET));
        assertEquals(ErrorCode.NO_ELIGIBLE_MODEL, e.getErrorCode());

        // CONFIDENTIAL ≤ CONFIDENTIAL → 放行
        gate.assertPlanAllowed(ctx("t_hot"), "plan_1", DataLabel.CONFIDENTIAL);
    }

    // ---------- 用例 2：PUBLIC / 缺装配 放行 ----------
    @Test
    void publicLabelOrMissingWiringPasses() {
        // 无 lookup（provider 缺失）→ currentLabel=PUBLIC，RoutingPlanExecutor 以此入参 → 放行
        PlanLabelGate noLookup = new PlanLabelGate(providerOf(null), providerOf(modelPlanGuard()));
        assertEquals(DataLabel.PUBLIC, noLookup.currentLabel(ctx("t_hot")));
        noLookup.assertPlanAllowed(ctx("t_hot"), "plan_1",
                noLookup.currentLabel(ctx("t_hot")));

        // 无 SinkGuard → 放行（模块单测装配）
        TurnLabelLookup lookup = threadId -> Optional.of(DataLabel.SECRET);
        PlanLabelGate noGuard = new PlanLabelGate(providerOf(lookup), providerOf(null));
        noGuard.assertPlanAllowed(ctx("t_hot"), "plan_1", DataLabel.SECRET);

        // PUBLIC 标签 + 两者齐全 → 放行
        PlanLabelGate full = new PlanLabelGate(providerOf(lookup), providerOf(modelPlanGuard()));
        full.assertPlanAllowed(ctx("t_public"), "plan_1", DataLabel.PUBLIC);
    }

    // ---------- 用例 3：binding 级剔除并记事件 ----------
    @Test
    void bindingFilterRemovesExceededAndRecordsEvent() {
        TurnLabelLookup lookup = threadId ->
                "t_hot".equals(threadId) ? Optional.of(DataLabel.SECRET) : Optional.empty();
        PlanLabelGate gate = new PlanLabelGate(providerOf(lookup), providerOf(modelPlanGuard()));
        RoutingTelemetry telemetry = mock(RoutingTelemetry.class);

        LabelModelPlanFilter filter = new LabelModelPlanFilter(gate,
                providerOf(modelPlanGuard()), telemetry);
        ModelEntities.ModelBinding binding = new ModelEntities.ModelBinding(
                "b_1", "lm_1", "prov_1", "gpt-x");

        // SECRET 超 MODEL_PLAN 许可 → 剔除
        ModelCallContext ctx = ctx("t_hot");
        assertEquals(false, filter.test(ctx, binding));
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(telemetry).recordEvent(eq("ws_1"), eq("t_hot"), eq("turn_1"),
                any(), any(), eq(RoutingTelemetry.KIND_FAIL), eq("LABEL"),
                any(), any(), eq("b_1"), any(), reason.capture(), any());
        assertTrue(reason.getValue().startsWith("40332"));

        // 无标签 thread（lookup empty → PUBLIC）/ 无 thread → 放行
        assertEquals(true, filter.test(ctx("t_public"), binding));
        assertEquals(true, filter.test(ctx(null), binding));
        assertEquals(true, filter.test(null, binding));
    }

    @Test
    void errorCodesRegistered() {
        assertEquals(42210, ErrorCode.NO_ELIGIBLE_MODEL.getCode());
        assertEquals(40332, ErrorCode.DATA_LABEL_EXCEEDED.getCode());
    }
}
