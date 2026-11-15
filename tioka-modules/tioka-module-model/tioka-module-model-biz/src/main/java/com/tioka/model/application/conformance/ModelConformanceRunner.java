package com.tioka.model.application.conformance;

import com.tioka.model.api.profile.StructuredCapabilityQueryApi.CapabilityRecord;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputCapability.MaxTokensAdvice;
import com.tioka.model.api.profile.StructuredOutputCapability.Stability;
import com.tioka.model.api.profile.StructuredOutputCapability.ThinkingCompat;
import com.tioka.model.api.profile.StructuredOutputMode;
import com.tioka.model.infrastructure.ModelStructuredCapabilityRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SCH-10（ADR-066 §10.2 / §10.4 闸门「模型启用结构化能力」）：模型接入测试。
 *
 * <p>对指定模型版本逐生成方式运行 {@link ConformanceProbeSet 一致性测试集}，测出
 * {@code modes}、{@code keywords}、{@code limits}、{@code thinkingCompat}、
 * {@code multimodalDowngrade} 的实际值，并在适配器预填的档案之上写回能力记录
 * （{@link ModelStructuredCapabilityRepository#put}）。
 *
 * <p>未被一致性测试测出的字段（{@code promptKeyword}、{@code maxTokensAdvice}、
 * {@code stability}、{@code refusalSignal}、{@code truncationSignal}）沿用已有记录，
 * 无记录时取默认值（ADR-066 §4：适配器可预填，测试只覆盖实测项）。
 *
 * <p>真实模型调用由 {@link ConformanceProbeExecutor} 提供；未装配实现时本类静默跳过。
 *
 * <p>口径说明：{@code limits} 取「按 §4.1 选择顺序首个可用方式」的实测上限（该方式即缺省被选用的方式）；
 * {@code keywords} 取严格档方式（{@code STRICT_SCHEMA}/{@code STRICT_TOOL}）实测接受集合的交集
 * ——两者同时被 {@code StructuredRequestBuilder} 的方言编译（{@code keywords}）与上限校验（{@code limits}）使用。
 */
@Service
public class ModelConformanceRunner {

    /** ADR-066 §4.1 的生成方式选择顺序。 */
    private static final List<StructuredOutputMode> SELECTION_ORDER = List.of(
            StructuredOutputMode.STRICT_SCHEMA,
            StructuredOutputMode.STRICT_TOOL,
            StructuredOutputMode.JSON_OBJECT,
            StructuredOutputMode.TOOL,
            StructuredOutputMode.PROMPT);

    private final ModelStructuredCapabilityRepository repository;
    private final ObjectProvider<ConformanceProbeExecutor> executors;

    public ModelConformanceRunner(ModelStructuredCapabilityRepository repository,
                                  ObjectProvider<ConformanceProbeExecutor> executors) {
        this.repository = repository;
        this.executors = executors;
    }

    /** 运行接入测试并写回能力记录；无执行器 / ref 为空时返回空。 */
    public Optional<CapabilityRecord> run(String modelRef) {
        return run(modelRef, Instant.now());
    }

    /** 可注入 {@code verifiedAt} 的口径（离线测试用）。 */
    Optional<CapabilityRecord> run(String modelRef, Instant verifiedAt) {
        ConformanceProbeExecutor executor = executors == null ? null : executors.getIfAvailable();
        if (executor == null || modelRef == null || modelRef.isBlank() || verifiedAt == null) {
            return Optional.empty();
        }
        Optional<CapabilityRecord> existing = repository.find(modelRef);
        StructuredOutputCapability base = existing.map(CapabilityRecord::capability).orElse(null);

        Map<StructuredOutputMode, ModeOutcome> measured = measure(modelRef, executor);

        Set<StructuredOutputMode> modes = EnumSet.noneOf(StructuredOutputMode.class);
        Map<StructuredOutputMode, ThinkingCompat> thinking = new EnumMap<>(StructuredOutputMode.class);
        measured.forEach((mode, outcome) -> {
            if (!outcome.usable()) {
                return;
            }
            modes.add(mode);
            thinking.put(mode, outcome.thinkingCompat() == null ? ThinkingCompat.UNVERIFIED : outcome.thinkingCompat());
        });

        StructuredOutputCapability capability = new StructuredOutputCapability(
                modes,
                keywordsOf(measured),
                limitsOf(measured),
                thinking,
                multimodalDowngrade(measured),
                base != null && base.promptKeyword(),
                base == null ? MaxTokensAdvice.UNSET : base.maxTokensAdvice(),
                base == null ? Stability.GA : base.stability(),
                base == null ? null : base.refusalSignal(),
                base == null ? null : base.truncationSignal(),
                verifiedAt.toString(),
                modes.isEmpty() ? "FAILED" : "PASSED");

        String version = existing.map(CapabilityRecord::version)
                .filter(v -> v != null && !v.isBlank())
                .orElse("conf-" + verifiedAt);
        CapabilityRecord record = new CapabilityRecord(capability, version);
        repository.put(modelRef, record);
        return Optional.of(record);
    }

    /** 逐方式运行测试集，聚合每个方式的探针结果。 */
    private Map<StructuredOutputMode, ModeOutcome> measure(String modelRef, ConformanceProbeExecutor executor) {
        Map<StructuredOutputMode, ModeOutcome> measured = new EnumMap<>(StructuredOutputMode.class);
        for (StructuredOutputMode mode : StructuredOutputMode.values()) {
            boolean baselineOk = false;
            boolean gatesOk = true;
            int maxDepth = 0;
            int maxProperties = 0;
            int maxEnumSize = 0;
            int maxUnionTypes = 0;
            Set<String> keywords = new LinkedHashSet<>();
            ThinkingCompat thinking = null;
            boolean multimodalUsable = false;

            for (ConformanceProbe probe : ConformanceProbeSet.probes()) {
                if (!applicable(probe, mode)) {
                    continue;
                }
                boolean passed = executor.execute(modelRef, mode, probe).passed();
                switch (probe.category()) {
                    case STRICT_BASELINE -> baselineOk = passed;
                    case KEYWORD -> {
                        if (passed) {
                            keywords.add(probe.strictKeyword());
                        }
                    }
                    case DEPTH -> maxDepth = passed ? Math.max(maxDepth, probe.level()) : maxDepth;
                    case SIZE -> maxProperties = passed ? Math.max(maxProperties, probe.level()) : maxProperties;
                    case ENUM -> maxEnumSize = passed ? Math.max(maxEnumSize, probe.level()) : maxEnumSize;
                    case NULLABLE -> maxUnionTypes = passed ? Math.max(maxUnionTypes, probe.level()) : maxUnionTypes;
                    case MULTIMODAL -> multimodalUsable = passed;
                    case THINKING -> thinking = passed ? ThinkingCompat.YES : ThinkingCompat.NO;
                    default -> {
                        // CJK_LONG：只作为输出质量的观测项，不参与能力推导
                    }
                }
                if (probe.gatesMode() && ConformanceProbeSet.withinPlatformCeiling(probe) && !passed) {
                    gatesOk = false;
                }
            }
            measured.put(mode, new ModeOutcome(baselineOk && gatesOk, Set.copyOf(keywords),
                    maxDepth, maxProperties, maxEnumSize, maxUnionTypes, thinking, multimodalUsable));
        }
        return measured;
    }

    /** 该探针是否需要对给定方式运行（严格档关键字只测严格方式；多模态降级只测 STRICT_SCHEMA）。 */
    private static boolean applicable(ConformanceProbe probe, StructuredOutputMode mode) {
        return switch (probe.category()) {
            case KEYWORD -> isStrict(mode);
            case MULTIMODAL -> mode == StructuredOutputMode.STRICT_SCHEMA;
            default -> true;
        };
    }

    /** 严格档方式实测接受的关键字交集；无严格档可用方式时为空集（§4：keywords 仅对严格档有意义）。 */
    private static Set<String> keywordsOf(Map<StructuredOutputMode, ModeOutcome> measured) {
        Set<String> intersection = null;
        for (StructuredOutputMode mode : SELECTION_ORDER) {
            ModeOutcome outcome = measured.get(mode);
            if (outcome == null || !outcome.usable() || !isStrict(mode)) {
                continue;
            }
            intersection = intersection == null
                    ? new LinkedHashSet<>(outcome.keywords())
                    : intersect(intersection, outcome.keywords());
        }
        return intersection == null ? Set.of() : Set.copyOf(intersection);
    }

    /** 取缺省被选用的方式（§4.1 顺序首个可用方式）的实测上限；无可用方式时为 null。 */
    private static StructuredOutputCapability.Limits limitsOf(Map<StructuredOutputMode, ModeOutcome> measured) {
        for (StructuredOutputMode mode : SELECTION_ORDER) {
            ModeOutcome outcome = measured.get(mode);
            if (outcome == null || !outcome.usable()) {
                continue;
            }
            if (outcome.maxDepth() == 0 && outcome.maxProperties() == 0
                    && outcome.maxEnumSize() == 0 && outcome.maxUnionTypes() == 0) {
                return null;
            }
            return new StructuredOutputCapability.Limits(
                    positive(outcome.maxDepth()), positive(outcome.maxProperties()),
                    positive(outcome.maxEnumSize()), positive(outcome.maxUnionTypes()), null);
        }
        return null;
    }

    /** 多模态输入下 STRICT_SCHEMA 是否降级（ADR-066 §4.1：降级即跳过该方式）。 */
    private static boolean multimodalDowngrade(Map<StructuredOutputMode, ModeOutcome> measured) {
        ModeOutcome strict = measured.get(StructuredOutputMode.STRICT_SCHEMA);
        return strict != null && strict.usable() && !strict.multimodalUsable();
    }

    private static Integer positive(int value) {
        return value > 0 ? value : null;
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        Set<String> out = new LinkedHashSet<>(left);
        out.retainAll(right);
        return out;
    }

    private static boolean isStrict(StructuredOutputMode mode) {
        return mode == StructuredOutputMode.STRICT_SCHEMA || mode == StructuredOutputMode.STRICT_TOOL;
    }

    /** 单个方式的探针聚合结果；{@code maxXxx} 为 0 表示该维度无通过项。 */
    private record ModeOutcome(boolean usable, Set<String> keywords, int maxDepth, int maxProperties,
                               int maxEnumSize, int maxUnionTypes, ThinkingCompat thinkingCompat,
                               boolean multimodalUsable) {
    }
}
