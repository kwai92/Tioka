package com.tioka.model.application.health;

import com.tioka.model.application.gateway.ModelBindingCooldownService;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.metrics.ModelMetrics;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.system.api.spi.SupplyQuotaPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MH-10（ADR-041 第 5.1 节）：内置运行状态。
 *
 * <p>把滚动窗口统计（{@link ModelCallStatsWindow}）、主动探测（{@link ModelProbeStore}）、
 * MH-03 冷却（{@link ModelBindingCooldownService}）与供给侧预算（{@link SupplyQuotaPort} + 用量）
 * 组装为 {@link ModelHealthSignals}，交 {@link ModelHealthEvaluator} 判定六态；供应商结论回写
 * {@code model_provider.health}（V236），其余经 {@link #snapshot} 只读暴露。
 *
 * <p>任一底层数据源缺席（Redis / 配额端口未接线）均按「无信号」降级，不抛异常、不影响调用路径。
 */
@Service
public class ModelHealthService {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthService.class);

    /** 短窗错误率 / P95 的观测窗口（分钟）。 */
    static final int SHORT_WINDOW_MINUTES = 5;

    private final ModelCallStatsWindow window;
    private final ModelProbeStore probeStore;
    private final ModelBindingCooldownService cooldown;
    private final ModelUsageRepository usages;
    private final ModelEntitiesRepository entities;
    private final ObjectProvider<SupplyQuotaPort> supplyQuotas;
    private final ObjectProvider<ModelMetrics> metrics;

    public ModelHealthService(ModelCallStatsWindow window,
                              ModelProbeStore probeStore,
                              ModelBindingCooldownService cooldown,
                              ModelUsageRepository usages,
                              ModelEntitiesRepository entities,
                              ObjectProvider<SupplyQuotaPort> supplyQuotas,
                              ObjectProvider<ModelMetrics> metrics) {
        this.window = window;
        this.probeStore = probeStore;
        this.cooldown = cooldown;
        this.usages = usages;
        this.entities = entities;
        this.supplyQuotas = supplyQuotas;
        this.metrics = metrics;
    }

    /** 判定指定作用对象的健康态。 */
    public ProviderHealthState evaluate(String dim, String id) {
        return ModelHealthEvaluator.evaluate(signals(dim, id));
    }

    /** 组装健康判定输入信号。 */
    public ModelHealthSignals signals(String dim, String id) {
        boolean disabled = isDisabled(dim, id);
        boolean budgetExhausted = ModelCallStatsWindow.DIM_PROVIDER.equals(dim) && providerBudgetExhausted(id);
        boolean cooling = ModelCallStatsWindow.DIM_BINDING.equals(dim) && cooldown != null
                && cooldown.isCooling(id);
        int probeFailures = ModelCallStatsWindow.DIM_PROVIDER.equals(dim) && probeStore != null
                ? probeStore.consecutiveFailures(id) : 0;
        WindowStats w5 = window == null ? WindowStats.empty() : window.lastMinutes(dim, id, SHORT_WINDOW_MINUTES);
        WindowStats w24 = window == null ? WindowStats.empty() : window.last24h(dim, id);
        return new ModelHealthSignals(disabled, budgetExhausted, cooling, probeFailures,
                w5.calls(), w5.errors(), w5.err429(),
                w5.percentileLatencyMs(0.95), w24.percentileLatencyMs(0.95));
    }

    /** 生成运行状态快照（供状态查询接口）。 */
    public ModelHealthSnapshot snapshot(String dim, String id) {
        WindowStats w60 = window == null ? WindowStats.empty() : window.last60m(dim, id);
        ModelCallStatsWindow.ErrorSample err = window == null ? null : window.lastError(dim, id);
        boolean circuitOpen = ModelCallStatsWindow.DIM_BINDING.equals(dim) && cooldown != null
                && cooldown.isCooling(id);
        Double tokenRatio = quotaRatio(dim, id, SupplyQuotaPort.METRIC_TOKENS_MONTH);
        Double costRatio = quotaRatio(dim, id, SupplyQuotaPort.METRIC_COST_MONTH);
        return new ModelHealthSnapshot(
                dim, id, evaluate(dim, id).name(),
                w60.calls(), round(w60.calls() / 60d),
                round(w60.errorRate()), round(w60.ratio429()),
                round(w60.percentileLatencyMs(0.50)), round(w60.percentileLatencyMs(0.95)),
                round(w60.percentileTtftMs(0.95)),
                w60.tokensIn(), w60.tokensOut(), w60.costMicros(),
                circuitOpen,
                ModelCallStatsWindow.DIM_PROVIDER.equals(dim) && probeStore != null
                        ? probeStore.consecutiveFailures(id) : 0,
                tokenRatio, costRatio,
                err == null ? null : err.errorClass(),
                err == null ? null : err.message(),
                err == null ? 0L : err.epochMs());
    }

    /** 汇总供应商健康快照（供状态查询接口）。 */
    public List<ModelHealthSnapshot> providerSnapshots() {
        List<ModelHealthSnapshot> out = new ArrayList<>();
        for (ModelProviderDO p : entities.providers()) {
            out.add(snapshot(ModelCallStatsWindow.DIM_PROVIDER, p.getId()));
        }
        return out;
    }

    /**
     * 重算并回写单个供应商的健康列（仅当与库中值不同才写，减少无谓更新）。
     *
     * @return 当前结论
     */
    public ProviderHealthState refreshProvider(String providerId) {
        ProviderHealthState state = evaluate(ModelCallStatsWindow.DIM_PROVIDER, providerId);
        Optional<ModelProviderDO> row = entities.getProvider(providerId);
        if (row.isPresent() && !state.name().equalsIgnoreCase(
                row.get().getHealth() == null ? "" : row.get().getHealth())) {
            try {
                entities.updateProviderHealth(providerId, state.name());
            } catch (RuntimeException e) {
                log.warn("write provider health failed providerId={}: {}", providerId, e.toString());
            }
        }
        updateQuotaGauges(providerId);
        return state;
    }

    /** MH-10（ADR-041 §5.2）：供给侧配额已用比例仪表（只输出供应商级总输出）。 */
    private void updateQuotaGauges(String providerId) {
        ModelMetrics m = metrics == null ? null : metrics.getIfAvailable();
        if (m == null) {
            return;
        }
        Double tokenRatio = quotaRatio(ModelCallStatsWindow.DIM_PROVIDER, providerId,
                SupplyQuotaPort.METRIC_TOKENS_MONTH);
        Double costRatio = quotaRatio(ModelCallStatsWindow.DIM_PROVIDER, providerId,
                SupplyQuotaPort.METRIC_COST_MONTH);
        if (tokenRatio != null) {
            m.setQuotaUsageRatio(SupplyQuotaPort.SCOPE_PROVIDER, providerId,
                    SupplyQuotaPort.METRIC_TOKENS_MONTH, tokenRatio);
        }
        if (costRatio != null) {
            m.setQuotaUsageRatio(SupplyQuotaPort.SCOPE_PROVIDER, providerId,
                    SupplyQuotaPort.METRIC_COST_MONTH, costRatio);
        }
    }

    /** 重算并回写全部供应商健康列（由探测调度周期调用）。 */
    public void refreshAllProviders() {
        try {
            for (ModelProviderDO p : entities.providers()) {
                refreshProvider(p.getId());
            }
        } catch (RuntimeException e) {
            log.warn("refresh all provider health failed: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- internals

    private boolean isDisabled(String dim, String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        if (ModelCallStatsWindow.DIM_PROVIDER.equals(dim)) {
            return entities.getProvider(id).isEmpty();
        }
        if (ModelCallStatsWindow.DIM_BINDING.equals(dim)) {
            Optional<ModelBindingDO> b = entities.getModelBinding(id);
            return b.isEmpty() || Boolean.FALSE.equals(b.get().getEnabled());
        }
        if (ModelCallStatsWindow.DIM_ALIAS.equals(dim)) {
            return !entities.hasAlias(id);
        }
        return false;
    }

    private boolean providerBudgetExhausted(String providerId) {
        Long tokenLimit = limit(providerId, SupplyQuotaPort.METRIC_TOKENS_MONTH);
        if (tokenLimit != null && usedTokens(providerId) >= tokenLimit) {
            return true;
        }
        Long costLimit = limit(providerId, SupplyQuotaPort.METRIC_COST_MONTH);
        return costLimit != null && usedCost(providerId) >= costLimit;
    }

    /** 供应商月度配额已用比例；无上限 / 非供应商维度返回 null。 */
    private Double quotaRatio(String dim, String id, String metric) {
        if (!ModelCallStatsWindow.DIM_PROVIDER.equals(dim)) {
            return null;
        }
        Long limit = limit(id, metric);
        if (limit == null || limit <= 0) {
            return null;
        }
        long used = SupplyQuotaPort.METRIC_COST_MONTH.equals(metric) ? usedCost(id) : usedTokens(id);
        return round((double) used / limit);
    }

    private Long limit(String providerId, String metric) {
        SupplyQuotaPort supply = supplyQuotas == null ? null : supplyQuotas.getIfAvailable();
        if (supply == null || providerId == null || providerId.isBlank()) {
            return null;
        }
        try {
            return supply.monthlyLimit(SupplyQuotaPort.SCOPE_PROVIDER, providerId, metric);
        } catch (RuntimeException e) {
            log.warn("read supply quota failed providerId={}: {}", providerId, e.toString());
            return null;
        }
    }

    private long usedTokens(String providerId) {
        Long v = usages.sumTokensByProvider(providerId, monthStart());
        return v == null ? 0L : v;
    }

    private long usedCost(String providerId) {
        Long v = usages.sumCostByProvider(providerId, monthStart());
        return v == null ? 0L : v;
    }

    private static Instant monthStart() {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    private static double round(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }
}
