package com.tioka.model.application.health;

import com.tioka.model.application.gateway.ModelBindingCooldownService;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.system.api.spi.SupplyQuotaPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MH-10（ADR-041 第 5.1 节）：健康聚合服务单测（mock 底层数据源，离线）。
 */
class ModelHealthServiceTest {

    private static final String PROVIDER = ModelCallStatsWindow.DIM_PROVIDER;
    private static final String BINDING = ModelCallStatsWindow.DIM_BINDING;

    private ModelCallStatsWindow window;
    private ModelProbeStore probeStore;
    private ModelBindingCooldownService cooldown;
    private ModelUsageRepository usages;
    private ModelEntitiesRepository entities;
    private SupplyQuotaPort supply;
    private ModelHealthService service;

    @BeforeEach
    void setUp() {
        window = mock(ModelCallStatsWindow.class);
        probeStore = mock(ModelProbeStore.class);
        cooldown = mock(ModelBindingCooldownService.class);
        usages = mock(ModelUsageRepository.class);
        entities = mock(ModelEntitiesRepository.class);
        supply = mock(SupplyQuotaPort.class);
        when(window.lastMinutes(anyString(), anyString(), anyInt())).thenReturn(WindowStats.empty());
        when(window.last60m(anyString(), anyString())).thenReturn(WindowStats.empty());
        when(window.last24h(anyString(), anyString())).thenReturn(WindowStats.empty());
        // 默认：供应商未配置配额上限（Mockito 对 Long 返回 0，须显式置空以走「无上限」分支）
        when(supply.monthlyLimit(anyString(), anyString(), anyString())).thenReturn(null);
        service = new ModelHealthService(window, probeStore, cooldown, usages, entities,
                providerOf(supply), providerOf(null));
    }

    @Test
    void providerMissingIsDisabled() {
        when(entities.getProvider("p1")).thenReturn(Optional.empty());
        assertEquals(ProviderHealthState.DISABLED, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void disabledBindingIsDisabled() {
        ModelBindingDO b = new ModelBindingDO();
        b.setId("b1");
        b.setEnabled(false);
        when(entities.getModelBinding("b1")).thenReturn(Optional.of(b));
        assertEquals(ProviderHealthState.DISABLED, service.evaluate(BINDING, "b1"));
    }

    @Test
    void budgetExhaustedWhenTokensOverLimit() {
        providerRow("p1");
        when(supply.monthlyLimit(SupplyQuotaPort.SCOPE_PROVIDER, "p1",
                SupplyQuotaPort.METRIC_TOKENS_MONTH)).thenReturn(1_000L);
        when(usages.sumTokensByProvider(anyString(), any(Instant.class))).thenReturn(1_500L);
        assertEquals(ProviderHealthState.BUDGET_EXHAUSTED, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void budgetExhaustedWhenCostOverLimit() {
        providerRow("p1");
        when(supply.monthlyLimit(SupplyQuotaPort.SCOPE_PROVIDER, "p1",
                SupplyQuotaPort.METRIC_COST_MONTH)).thenReturn(500L);
        when(usages.sumCostByProvider(anyString(), any(Instant.class))).thenReturn(500L);
        assertEquals(ProviderHealthState.BUDGET_EXHAUSTED, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void coolingBindingIsCooldown() {
        ModelBindingDO b = new ModelBindingDO();
        b.setId("b1");
        b.setEnabled(true);
        when(entities.getModelBinding("b1")).thenReturn(Optional.of(b));
        when(cooldown.isCooling("b1")).thenReturn(true);
        assertEquals(ProviderHealthState.COOLDOWN, service.evaluate(BINDING, "b1"));
    }

    @Test
    void downByProbeFailures() {
        providerRow("p1");
        when(probeStore.consecutiveFailures("p1")).thenReturn(ModelHealthEvaluator.DOWN_PROBE_FAILURES);
        assertEquals(ProviderHealthState.DOWN, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void downByShortWindowErrorRate() {
        providerRow("p1");
        when(window.lastMinutes(anyString(), anyString(), anyInt())).thenReturn(stats(10, 6, 0));
        assertEquals(ProviderHealthState.DOWN, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void degradedBy429Ratio() {
        providerRow("p1");
        when(window.lastMinutes(anyString(), anyString(), anyInt())).thenReturn(stats(10, 0, 2));
        assertEquals(ProviderHealthState.DEGRADED, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void defaultIsUp() {
        providerRow("p1");
        assertEquals(ProviderHealthState.UP, service.evaluate(PROVIDER, "p1"));
    }

    @Test
    void refreshProviderWritesOnlyWhenChanged() {
        ModelProviderDO row = providerRow("p1");
        when(probeStore.consecutiveFailures("p1")).thenReturn(3);
        assertEquals(ProviderHealthState.DOWN, service.refreshProvider("p1"));
        verify(entities).updateProviderHealth("p1", "DOWN");

        // 已是同一结论 → 不重复写库（总写入次数仍为 1）
        row.setHealth("DOWN");
        service.refreshProvider("p1");
        verify(entities, times(1)).updateProviderHealth("p1", "DOWN");
    }

    @Test
    void snapshotExposesWindowAndLastError() {
        providerRow("p1");
        when(window.last60m(PROVIDER, "p1")).thenReturn(stats(120, 12, 6));
        when(window.lastError(PROVIDER, "p1")).thenReturn(
                new ModelCallStatsWindow.ErrorSample("429", "rate limited", 1_700_000_000_000L, null));
        when(probeStore.consecutiveFailures("p1")).thenReturn(1);

        ModelHealthSnapshot snap = service.snapshot(PROVIDER, "p1");
        assertEquals(PROVIDER, snap.dim());
        assertEquals("p1", snap.id());
        assertEquals(120L, snap.calls60m());
        assertEquals(2.0d, snap.callsPerMin(), 1e-9);
        assertEquals("429", snap.lastErrorClass());
        assertEquals("rate limited", snap.lastErrorMessage());
        assertEquals(1, snap.probeConsecutiveFailures());
    }

    // ------------------------------------------------------------------ helpers

    private ModelProviderDO providerRow(String id) {
        ModelProviderDO p = new ModelProviderDO();
        p.setId(id);
        p.setBaseUrl("https://api.example.com");
        when(entities.getProvider(id)).thenReturn(Optional.of(p));
        return p;
    }

    private static WindowStats stats(long calls, long errOther, long err429) {
        Map<Object, Object> m = new HashMap<>();
        m.put("calls", calls);
        m.put("err_other", errOther);
        m.put("err_429", err429);
        return WindowStats.merge(List.of(m));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
