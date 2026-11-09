package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.domain.ModelEntities.ModelBinding;
import com.tioka.model.infrastructure.ModelUsageRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * MH 供给侧月度预算守卫（P2-L5 / GAP-L5-1）。
 *
 * <p>预算挂在 binding 维度（V218 {@code model_binding.budget_token_month / budget_cost_month}，
 * NULL = 不限），窗口为系统时区自然月（与 {@code SystemCostController} 的缺省汇总窗口一致）。
 * 在模型网关发起 provider 调用<b>之前</b>预检：超限即熔断，避免产生不可控成本。
 *
 * <p>判定规则（任一命中即熔断）：
 * <ul>
 *     <li>token 预算：本月已用 + 本次预估 &gt; {@code budgetTokenMonth}；</li>
 *     <li>成本预算：本月已用（微美分）&ge; {@code budgetCostMonth}——当次成本预估不可得，以已用口径熔断。</li>
 * </ul>
 */
@Component
public class ModelBudgetGuard {
    private final ModelUsageRepository usages;

    public ModelBudgetGuard(ModelUsageRepository usages) {
        this.usages = usages;
    }

    /**
     * 预检指定 binding 的月度预算；超限抛 {@link ErrorCode#MODEL_BUDGET_EXHAUSTED}。
     *
     * @param binding         已解析的绑定快照；null 时直接放行（无预算语义）
     * @param estimatedTokens 本次调用的预估 token 数（调用方负责估算，如 {@code estimateTokens}）
     */
    public void check(ModelBinding binding, long estimatedTokens) {
        if (binding == null) {
            return;
        }
        Instant monthStart = LocalDate.now(ZoneId.systemDefault())
                .withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        if (binding.budgetTokenMonth() != null) {
            long used = usages.sumTokensByBinding(binding.id(), monthStart);
            long projected = used + Math.max(0L, estimatedTokens);
            if (projected > binding.budgetTokenMonth()) {
                throw new BizException(ErrorCode.MODEL_BUDGET_EXHAUSTED,
                        "binding 本月 token 预算已耗尽: " + binding.id()
                                + "（已用 " + used + " + 预估 " + Math.max(0L, estimatedTokens)
                                + " > 上限 " + binding.budgetTokenMonth() + "）");
            }
        }

        if (binding.budgetCostMonth() != null) {
            long usedCost = usages.sumCostByBinding(binding.id(), monthStart);
            if (usedCost >= binding.budgetCostMonth()) {
                throw new BizException(ErrorCode.MODEL_BUDGET_EXHAUSTED,
                        "binding 本月成本预算已耗尽: " + binding.id()
                                + "（已用 " + usedCost + " >= 上限 " + binding.budgetCostMonth() + " 微美分）");
            }
        }
    }
}
