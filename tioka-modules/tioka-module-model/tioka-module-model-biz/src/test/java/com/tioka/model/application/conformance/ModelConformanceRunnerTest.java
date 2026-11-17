package com.tioka.model.application.conformance;

import com.tioka.model.api.profile.StructuredCapabilityQueryApi.CapabilityRecord;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputCapability.MaxTokensAdvice;
import com.tioka.model.api.profile.StructuredOutputCapability.Stability;
import com.tioka.model.api.profile.StructuredOutputCapability.ThinkingCompat;
import com.tioka.model.api.profile.StructuredOutputMode;
import com.tioka.model.application.conformance.ConformanceProbeExecutor.ProbeOutcome;
import com.tioka.model.infrastructure.ModelStructuredCapabilityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SCH-10（ADR-066 §10.2）：模型接入测试的能力推导与写回（离线，探针执行器以桩替）。
 */
class ModelConformanceRunnerTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-11-15T00:00:00Z");
    private static final String REF = "qwen@qwen3-max";

    /** 严格档下被拒绝的关键字（ADR-066 §4 例：DeepSeek 严格工具不含长度与数组项约束）。 */
    private static final Set<String> UNSUPPORTED_KEYWORDS =
            Set.of("minLength", "maxLength", "minItems", "maxItems");

    @Test
    @DisplayName("严格档方式：测出 modes/keywords/limits/thinkingCompat/multimodalDowngrade 并写回（预填字段保留）")
    void derivesCapabilityFromProbes() {
        FakeRepository repository = new FakeRepository();
        repository.put(REF, new CapabilityRecord(prefilled(), "v3"));

        ModelConformanceRunner runner = runner(repository, new StubExecutor((mode, probe) -> {
            if (mode == StructuredOutputMode.STRICT_TOOL) {
                return false; // 该模型严格工具不可用
            }
            if (mode == StructuredOutputMode.STRICT_SCHEMA) {
                return switch (probe.category()) {
                    case KEYWORD -> !UNSUPPORTED_KEYWORDS.contains(probe.strictKeyword());
                    case MULTIMODAL -> false;   // 有图片时降级
                    case THINKING -> false;     // 严格档不能与思考模式同用
                    default -> true;
                };
            }
            return true;
        }));

        CapabilityRecord record = runner.run(REF, VERIFIED_AT).orElseThrow();
        StructuredOutputCapability cap = record.capability();

        assertEquals(Set.of(StructuredOutputMode.STRICT_SCHEMA, StructuredOutputMode.JSON_OBJECT,
                StructuredOutputMode.TOOL, StructuredOutputMode.PROMPT), cap.modes());
        assertEquals(Set.of("type", "properties", "required", "additionalProperties", "description",
                "enum", "items", "anyOf", "$defs", "$ref"), cap.keywords());
        // limits 取缺省被选用的方式（STRICT_SCHEMA）的实测上限；maxStrictTools 探针不覆盖
        assertEquals(8, cap.limits().maxDepth());
        assertEquals(150, cap.limits().maxProperties());
        assertEquals(300, cap.limits().maxEnumSize());
        assertEquals(24, cap.limits().maxUnionTypes());
        assertNull(cap.limits().maxStrictTools());
        assertEquals(ThinkingCompat.NO, cap.thinkingCompat().get(StructuredOutputMode.STRICT_SCHEMA));
        assertEquals(ThinkingCompat.YES, cap.thinkingCompat().get(StructuredOutputMode.JSON_OBJECT));
        assertTrue(cap.multimodalDowngrade());
        // 未实测字段沿用适配器预填
        assertTrue(cap.promptKeyword());
        assertEquals(MaxTokensAdvice.ESTIMATE, cap.maxTokensAdvice());
        assertEquals(Stability.BETA, cap.stability());
        assertEquals("refusal", cap.refusalSignal());
        assertEquals("length", cap.truncationSignal());
        // 实测时间与结果
        assertEquals(VERIFIED_AT.toString(), cap.verifiedAt());
        assertEquals("PASSED", cap.conformance());
        assertEquals("v3", record.version());

        assertEquals(2, repository.puts, "写回一次（预置 + 测试结果）");
        assertEquals(record, repository.find(REF).orElseThrow());
    }

    @Test
    @DisplayName("无可用方式：modes 为空、conformance=FAILED、limits 为空")
    void reportsFailureWhenNoModeUsable() {
        FakeRepository repository = new FakeRepository();
        ModelConformanceRunner runner = runner(repository, new StubExecutor((mode, probe) -> false));

        CapabilityRecord record = runner.run(REF, VERIFIED_AT).orElseThrow();
        StructuredOutputCapability cap = record.capability();

        assertTrue(cap.modes().isEmpty());
        assertTrue(cap.keywords().isEmpty());
        assertNull(cap.limits());
        assertFalse(cap.multimodalDowngrade());
        assertEquals("FAILED", cap.conformance());
        assertEquals("conf-" + VERIFIED_AT, record.version());
    }

    @Test
    @DisplayName("未装配探针执行器：静默跳过，不写回能力记录")
    void skipsWhenNoExecutor() {
        FakeRepository repository = new FakeRepository();
        ModelConformanceRunner runner = runner(repository, null);

        assertTrue(runner.run(REF, VERIFIED_AT).isEmpty());
        assertEquals(0, repository.puts);
    }

    // ==================== 夹具 ====================

    @SuppressWarnings("unchecked")
    private static ModelConformanceRunner runner(ModelStructuredCapabilityRepository repository,
                                                 ConformanceProbeExecutor executor) {
        ObjectProvider<ConformanceProbeExecutor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(executor);
        return new ModelConformanceRunner(repository, provider);
    }

    private static StructuredOutputCapability prefilled() {
        return new StructuredOutputCapability(
                Set.of(StructuredOutputMode.STRICT_SCHEMA),
                Set.of("type"),
                null,
                Map.of(),
                false,
                true,
                MaxTokensAdvice.ESTIMATE,
                Stability.BETA,
                "refusal",
                "length",
                null,
                null);
    }

    private static final class StubExecutor implements ConformanceProbeExecutor {
        private final BiPredicate<StructuredOutputMode, ConformanceProbe> rule;

        StubExecutor(BiPredicate<StructuredOutputMode, ConformanceProbe> rule) {
            this.rule = rule;
        }

        @Override
        public ProbeOutcome execute(String modelRef, StructuredOutputMode mode, ConformanceProbe probe) {
            return rule.test(mode, probe) ? ProbeOutcome.pass() : ProbeOutcome.fail("probe_failed");
        }
    }

    private static final class FakeRepository implements ModelStructuredCapabilityRepository {
        private final Map<String, CapabilityRecord> store = new LinkedHashMap<>();
        private int puts;

        @Override
        public Optional<CapabilityRecord> find(String modelRef) {
            return Optional.ofNullable(store.get(modelRef));
        }

        @Override
        public void put(String modelRef, CapabilityRecord record) {
            store.put(modelRef, record);
            puts++;
        }
    }

    /** 探针集自身的不变量：十类维度齐全，且上限以内/以外各有所覆盖。 */
    @Test
    @DisplayName("测试集覆盖 ADR-066 §10.2 列举的十类维度")
    void probeSetCoversAllDimensions() {
        List<ConformanceProbe> probes = ConformanceProbeSet.probes();
        Set<ConformanceProbe.Category> categories = probes.stream()
                .map(ConformanceProbe::category)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(ConformanceProbe.Category.values()), categories);
        assertTrue(probes.stream().anyMatch(p -> !ConformanceProbeSet.withinPlatformCeiling(p)),
                "须含超出平台上限的探针以测出真实上限");
    }
}
