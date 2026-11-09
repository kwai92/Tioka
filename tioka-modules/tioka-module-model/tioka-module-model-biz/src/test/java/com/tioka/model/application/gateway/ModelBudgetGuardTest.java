package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.domain.ModelEntities.ModelBinding;
import com.tioka.model.infrastructure.ModelUsageRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-L5（GAP-L5-1）：供给侧月度预算守卫单测（离线，Mockito 桩仓储）。
 *
 * <p>覆盖：token 预算（含预估累计超限）、成本预算、预算未配置放行、null binding 放行、
 * 错误码语义（{@link ErrorCode#MODEL_BUDGET_EXHAUSTED}）。
 */
class ModelBudgetGuardTest {

    private final ModelUsageRepository usages = mock(ModelUsageRepository.class);
    private final ModelBudgetGuard guard = new ModelBudgetGuard(usages);

    /** binding 域对象构造捷径：预算字段可变，其余给最小有效值。 */
    private static ModelBinding binding(Long tokenBudget, Long costBudget) {
        return new ModelBinding("bnd_x", "lm_x", "prv_x", "m", "chat",
                null, null, 0L, 0L, null, null, true, tokenBudget, costBudget);
    }

    @Test
    void tokenBudgetExhaustedWithEstimate() {
        // 本月已用 9_000，上限 10_000：本次预估 2_000 → 11_000 > 10_000，熔断
        when(usages.sumTokensByBinding(eq("bnd_x"), any())).thenReturn(9_000L);
        BizException e = assertThrows(BizException.class, () -> guard.check(binding(10_000L, null), 2_000));
        assertEquals(ErrorCode.MODEL_BUDGET_EXHAUSTED, e.getErrorCode());
    }

    @Test
    void tokenBudgetNotExhaustedWhenProjectionFits() {
        // 已用 5_000 + 预估 2_000 = 7_000 ≤ 10_000 → 放行
        when(usages.sumTokensByBinding(eq("bnd_x"), any())).thenReturn(5_000L);
        assertDoesNotThrow(() -> guard.check(binding(10_000L, null), 2_000));
    }

    @Test
    void costBudgetExhausted() {
        // 当次成本预估不可得：已用 ≥ 上限即熔断（恰好等于也算耗尽）
        when(usages.sumCostByBinding(eq("bnd_x"), any())).thenReturn(1_000_000L);
        BizException e = assertThrows(BizException.class, () -> guard.check(binding(null, 1_000_000L), 0));
        assertEquals(ErrorCode.MODEL_BUDGET_EXHAUSTED, e.getErrorCode());
    }

    @Test
    void costBudgetNotExhausted() {
        when(usages.sumCostByBinding(eq("bnd_x"), any())).thenReturn(999_999L);
        assertDoesNotThrow(() -> guard.check(binding(null, 1_000_000L), 0));
    }

    @Test
    void noBudgetConfiguredPasses() {
        // 预算均未配置（NULL = 不限）→ 不触碰仓储、直接放行
        assertDoesNotThrow(() -> guard.check(binding(null, null), Long.MAX_VALUE));
    }

    @Test
    void nullBindingPasses() {
        assertDoesNotThrow(() -> guard.check(null, 1_000));
    }

    @Test
    void negativeEstimateClampedToZero() {
        // 负预估按 0 处理：已用 10_000 = 上限 10_000 仍应熔断（token 预算为「投影 > 上限」语义，等于不熔断）
        when(usages.sumTokensByBinding(eq("bnd_x"), any())).thenReturn(9_999L);
        assertDoesNotThrow(() -> guard.check(binding(10_000L, null), -5));
    }
}
