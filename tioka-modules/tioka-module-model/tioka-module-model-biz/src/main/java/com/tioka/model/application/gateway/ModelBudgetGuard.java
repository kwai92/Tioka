package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.domain.ModelEntities.ModelBinding;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.system.api.spi.SupplyQuotaPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 供给侧月度预算守卫（P2-L5 / GAP-L5-1；MH-09 / ADR-041 第 3 节）。
 *
 * <p>两个维度，窗口均为系统时区自然月（与 {@code SystemCostController} 的缺省汇总窗口一致）：
 * <ul>
 *     <li><b>binding</b>：上限取自 {@code model_binding.budget_token_month / budget_cost_month}（V218，NULL = 不限）；</li>
 *     <li><b>provider</b>：上限取自 {@code gov_quota}（{@code scope_type = provider}，V152），
 *     经 {@link SupplyQuotaPort} 读取；已用按 {@code model_usage.provider_id} 聚合。</li>
 * </ul>
 *
 * <p>在模型网关发起 provider 调用<b>之前</b>预检：超限即熔断，避免产生不可控成本。
 * 判定规则（任一命中即熔断）：token 预算「本月已用 + 本次预估 &gt; 上限」；
 * 成本预算「本月已用 ≥ 上限」（当次成本预估不可得，以已用口径熔断）。
 */
@Component
public class ModelBudgetGuard {
    private final ModelUsageRepository usages;
    private final ObjectProvider<SupplyQuotaPort> supplyQuotas;

    /** 兼容构造（无供给侧配额端口）：仅 binding 维度预算生效。 */
    public ModelBudgetGuard(ModelUsageRepository usages) {
        this(usages, (ObjectProvider<SupplyQuotaPort>) null);
    }

    @Autowired
    public ModelBudgetGuard(ModelUsageRepository usages, ObjectProvider<SupplyQuotaPort> supplyQuotas) {
        this.usages = usages;
        this.supplyQuotas = supplyQuotas;
    }

    /**
     * 预检指定 binding 与其所属供应商的月度预算；超限抛 {@link ErrorCode#MODEL_BUDGET_EXHAUSTED}。
     *
     * @param binding         已解析的绑定快照；null 时直接放行（无预算语义）
     * @param estimatedTokens 本次调用的预估 token 数（调用方负责估算，如 {@code estimateTokens}）
     */
    public void check(ModelBinding binding, long estimatedTokens) {
        if (binding == null) {
            return;
        }
        Instant monthStart = monthStart();
        checkBinding(binding, estimatedTokens, monthStart);
        checkProvider(binding, estimatedTokens, monthStart);
    }

    /** binding 维度月度预算（V218 列）。 */
    private void checkBinding(ModelBinding binding, long estimatedTokens, Instant monthStart) {
        long est = Math.max(0L, estimatedTokens);
        if (binding.budgetTokenMonth() != null) {
            long used = usages.sumTokensByBinding(binding.id(), monthStart);
            if (used + est > binding.budgetTokenMonth()) {
                throw exhausted("binding", binding.id(), "token", used, est, binding.budgetTokenMonth());
            }
        }
        if (binding.budgetCostMonth() != null) {
            long used = usages.sumCostByBinding(binding.id(), monthStart);
            if (used >= binding.budgetCostMonth()) {
                throw exhausted("binding", binding.id(), "成本", used, 0, binding.budgetCostMonth());
            }
        }
    }

    /**
     * 供应商维度月度预算（MH-09）：上限来自 {@code gov_quota}（provider scope），
     * 已用按 {@code model_usage.provider_id} 聚合；未接线或未配置时放行。
     */
    private void checkProvider(ModelBinding binding, long estimatedTokens, Instant monthStart) {
        SupplyQuotaPort supply = supplyQuotas == null ? null : supplyQuotas.getIfAvailable();
        String providerId = binding.providerId();
        if (supply == null || providerId == null || providerId.isBlank()) {
            return;
        }
        long est = Math.max(0L, estimatedTokens);
        Long tokenLimit = supply.monthlyLimit(SupplyQuotaPort.SCOPE_PROVIDER, providerId,
                SupplyQuotaPort.METRIC_TOKENS_MONTH);
        if (tokenLimit != null) {
            long used = usages.sumTokensByProvider(providerId, monthStart);
            if (used + est > tokenLimit) {
                throw exhausted("provider", providerId, "token", used, est, tokenLimit);
            }
        }
        Long costLimit = supply.monthlyLimit(SupplyQuotaPort.SCOPE_PROVIDER, providerId,
                SupplyQuotaPort.METRIC_COST_MONTH);
        if (costLimit != null) {
            long used = usages.sumCostByProvider(providerId, monthStart);
            if (used >= costLimit) {
                throw exhausted("provider", providerId, "成本", used, 0, costLimit);
            }
        }
    }

    private static Instant monthStart() {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    private static BizException exhausted(String dimension, String id, String unit,
                                          long used, long est, long limit) {
        String detail = est > 0
                ? "（已用 " + used + " + 预估 " + est + " > 上限 " + limit + "）"
                : "（已用 " + used + " >= 上限 " + limit + "）";
        return new BizException(ErrorCode.MODEL_BUDGET_EXHAUSTED,
                dimension + " 本月 " + unit + " 预算已耗尽: " + id + detail);
    }
}
