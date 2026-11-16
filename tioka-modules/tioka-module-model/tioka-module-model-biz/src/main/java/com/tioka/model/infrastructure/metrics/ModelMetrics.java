package com.tioka.model.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MH-10（ADR-041 第 5.2 节 / GOV-08 指标清单）：模型域 Prometheus 指标。
 *
 * <p>以 Micrometer {@link MeterRegistry} 为出口（生产环境由 tioka-server 装配 Prometheus 注册表；
 * 指标名与标签口径以 {@code docs/architecture/ops/指标清单.md} 为准，不在本类改名）。
 * 注册表缺席（未装配 actuator/registry）时全部降级为空操作，不影响调用路径。
 *
 * <p>基数规则：不带用户、会话、Turn 标签。
 */
@Component
public class ModelMetrics {

    // ---- 指标名（GOV-08 契约，禁止改名） ----
    public static final String CALLS = "tioka_model_calls_total";
    public static final String ERRORS = "tioka_model_errors_total";
    public static final String LATENCY = "tioka_model_latency_seconds";
    public static final String TTFT = "tioka_model_ttft_seconds";
    public static final String TOKENS = "tioka_model_tokens_total";
    public static final String COST_MICROS = "tioka_model_cost_micros_total";
    public static final String FAILOVER = "tioka_model_failover_total";
    public static final String ROUTE_DECISIONS = "tioka_model_route_decisions_total";
    public static final String CIRCUIT_STATE = "tioka_model_circuit_state";
    public static final String QUOTA_USAGE_RATIO = "tioka_quota_usage_ratio";
    /** AST-07（ADR-041 §5.2）：Prompt 渲染错误（标签 template）。 */
    public static final String PROMPT_RENDER_ERRORS = "tioka_prompt_render_errors_total";

    private final ObjectProvider<MeterRegistry> registries;
    private final ConcurrentMap<String, AtomicReference<Double>> circuitStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicReference<Double>> quotaRatios = new ConcurrentHashMap<>();

    /**
     * Spring 装配用构造器。
     *
     * <p><b>必须显式标注 {@code @Autowired}</b>：本类有两个 public 构造器（另一个供离线单测直接
     * 注入 {@code MeterRegistry}）。Spring 在多候选构造器且无标注时会回退去找无参构造器，
     * 找不到即抛 {@code Failed to instantiate [...]: No default constructor found}，
     * 导致依赖它的 {@code DefaultModelGateway} → {@code KnowledgeService} →
     * {@code TiokaMcpOutboundServer} 整条链装配失败，所有 {@code @SpringBootTest} 起不来。
     * 该缺陷由 CI 的 backend job 暴露（run 43907040425），本地离线单测因直接 new 而不复现。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelMetrics(ObjectProvider<MeterRegistry> registries) {
        this.registries = registries;
    }

    /** 供离线单测直接注入注册表。 */
    public ModelMetrics(MeterRegistry registry) {
        this(new FixedProvider(registry));
    }

    // ---------------------------------------------------------------- 计数 / 直方图

    /** 调用计数（provider / binding / alias / purpose / status）。 */
    public void recordCall(String provider, String binding, String alias, String purpose, String status) {
        counter(CALLS, "provider", provider, "binding", binding, "alias", alias,
                "purpose", purpose, "status", status);
    }

    /** 错误计数（provider / binding / error_class）。 */
    public void recordError(String provider, String binding, String errorClass) {
        counter(ERRORS, "provider", provider, "binding", binding, "error_class", errorClass);
    }

    /** 延迟直方图（provider / binding / purpose），单位秒。 */
    public void recordLatency(String provider, String binding, String purpose, long latencyMs) {
        MeterRegistry r = reg();
        if (r == null) {
            return;
        }
        Timer.builder(LATENCY)
                .tag("provider", tag(provider))
                .tag("binding", tag(binding))
                .tag("purpose", tag(purpose))
                .register(r)
                .record(Duration.ofMillis(Math.max(0L, latencyMs)));
    }

    /** 首 token 延迟直方图（provider / binding），单位秒。 */
    public void recordTtft(String provider, String binding, long ttftMs) {
        MeterRegistry r = reg();
        if (r == null) {
            return;
        }
        Timer.builder(TTFT)
                .tag("provider", tag(provider))
                .tag("binding", tag(binding))
                .register(r)
                .record(Duration.ofMillis(Math.max(0L, ttftMs)));
    }

    /** token 计数（provider / binding / direction ∈ in|out|cached）。 */
    public void recordTokens(String provider, String binding, String direction, long tokens) {
        if (tokens <= 0) {
            return;
        }
        MeterRegistry r = reg();
        if (r == null) {
            return;
        }
        Counter.builder(TOKENS)
                .tag("provider", tag(provider))
                .tag("binding", tag(binding))
                .tag("direction", tag(direction))
                .register(r)
                .increment(tokens);
    }

    /** 成本计数（provider / binding），单位微元。 */
    public void recordCostMicros(String provider, String binding, long micros) {
        if (micros <= 0) {
            return;
        }
        MeterRegistry r = reg();
        if (r == null) {
            return;
        }
        Counter.builder(COST_MICROS)
                .tag("provider", tag(provider))
                .tag("binding", tag(binding))
                .register(r)
                .increment(micros);
    }

    /** 故障接力计数（from_binding / reason）。 */
    public void recordFailover(String fromBinding, String reason) {
        counter(FAILOVER, "from_binding", fromBinding, "reason", reason);
    }

    /** 路由决策计数（plan / group / decider / outcome）。 */
    public void recordRouteDecision(String plan, String group, String decider, String outcome) {
        counter(ROUTE_DECISIONS, "plan", plan, "group", group, "decider", decider, "outcome", outcome);
    }

    /** Prompt 渲染错误计数（template）。AST-07（ADR-041 §5.2）。 */
    public void recordPromptRenderError(String template) {
        counter(PROMPT_RENDER_ERRORS, "template", template);
    }

    // ---------------------------------------------------------------- 仪表

    /**
     * 熔断状态仪表（binding）。取值：{@code 0}=闭合 / 正常，{@code 1}=冷却，{@code 2}=打开。
     */
    public void setCircuitState(String binding, double state) {
        MeterRegistry r = reg();
        if (r == null || blank(binding)) {
            return;
        }
        AtomicReference<Double> ref = circuitStates.computeIfAbsent(binding, b -> {
            AtomicReference<Double> v = new AtomicReference<>(0d);
            Gauge.builder(CIRCUIT_STATE, v, x -> x.get() == null ? 0d : x.get())
                    .tag("binding", b)
                    .register(r);
            return v;
        });
        ref.set(state);
    }

    /** 配额已用比例仪表（scope_type / scope_id / metric），取值 0..1+。 */
    public void setQuotaUsageRatio(String scopeType, String scopeId, String metric, double ratio) {
        MeterRegistry r = reg();
        if (r == null || blank(scopeId)) {
            return;
        }
        String key = tag(scopeType) + "|" + scopeId + "|" + tag(metric);
        AtomicReference<Double> ref = quotaRatios.computeIfAbsent(key, k -> {
            AtomicReference<Double> v = new AtomicReference<>(0d);
            Gauge.builder(QUOTA_USAGE_RATIO, v, x -> x.get() == null ? 0d : x.get())
                    .tag("scope_type", tag(scopeType))
                    .tag("scope_id", scopeId)
                    .tag("metric", tag(metric))
                    .register(r);
            return v;
        });
        ref.set(Math.max(0d, ratio));
    }

    // ---------------------------------------------------------------- internals

    private void counter(String name, String... kv) {
        MeterRegistry r = reg();
        if (r == null) {
            return;
        }
        Counter.Builder b = Counter.builder(name);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.tag(kv[i], tag(kv[i + 1]));
        }
        b.register(r).increment();
    }

    private MeterRegistry reg() {
        return registries == null ? null : registries.getIfAvailable();
    }

    private static String tag(String v) {
        return v == null || v.isBlank() ? "" : v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 固定返回同一注册表的 ObjectProvider（仅供单测构造使用）。 */
    private static final class FixedProvider implements ObjectProvider<MeterRegistry> {
        private final MeterRegistry registry;

        FixedProvider(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public MeterRegistry getObject(Object... args) {
            return registry;
        }

        @Override
        public MeterRegistry getIfAvailable() {
            return registry;
        }

        @Override
        public MeterRegistry getIfUnique() {
            return registry;
        }

        @Override
        public MeterRegistry getObject() {
            return registry;
        }

        @Override
        public java.util.Iterator<MeterRegistry> iterator() {
            return java.util.List.of(registry).iterator();
        }
    }
}
