package com.tioka.system.api.spi;

/**
 * 供给侧预算上限查询（ADR-041 第 3 节 / MH-09）。
 *
 * <p>上限存于 {@code gov_quota}（{@code scope_type = provider / alias}，V152 起 {@code warn_ratio} 预警比例）。
 * 由 model-biz 的供给守卫（{@code ModelBudgetGuard}）消费：命中即让路由器跳过候选，
 * 全部耗尽才抛 {@code MODEL_BUDGET_EXHAUSTED}。
 *
 * <p>读配额时不认识的作用对象按「不限」处理（约束 F13）。
 */
public interface SupplyQuotaPort {

    /** 供给侧作用对象：供应商（月度 token / 成本）。 */
    String SCOPE_PROVIDER = "provider";
    /** 供给侧作用对象：模型别名（月度成本）。 */
    String SCOPE_ALIAS = "alias";
    /** 月度 token 指标。 */
    String METRIC_TOKENS_MONTH = "tokens_month";
    /** 月度成本指标（微美分）。 */
    String METRIC_COST_MONTH = "cost_month";

    /**
     * @param scopeType {@link #SCOPE_PROVIDER} / {@link #SCOPE_ALIAS}
     * @param scopeId   供应商或别名标识
     * @param metric    {@link #METRIC_TOKENS_MONTH} / {@link #METRIC_COST_MONTH}
     * @return 上限；未配置或作用对象不认识时返回 {@code null}（按「不限」处理）
     */
    Long monthlyLimit(String scopeType, String scopeId, String metric);
}
