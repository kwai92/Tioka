package com.tioka.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.api.spi.EvalScore;
import com.tioka.eval.api.spi.EvalSubject;
import com.tioka.eval.api.spi.Evaluator;
import com.tioka.eval.evaluator.CitationEvaluator;
import com.tioka.eval.evaluator.ContainsEvaluator;
import com.tioka.eval.evaluator.EvaluatorRegistry;
import com.tioka.eval.evaluator.ExactEvaluator;
import com.tioka.eval.evaluator.LlmJudgeEvaluator;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVO-02 评估器 SPI 验收（ADR-031）：注册表按 type 收集；exact / contains / citation
 * 纯逻辑逐个断言；llm_judge 用内存 ModelGateway 替身验证 JSON 解析与降级路径。
 * 离线内存替身（同 FeedbackIT 做法，不起 Spring）。
 */
class EvaluatorSpiIT {

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // SPI 注册表
    // ------------------------------------------------------------------

    @Test
    void registryCollectsByTypeAndToleratesUnknown() {
        EvaluatorRegistry registry = new EvaluatorRegistry(List.of(
                new ExactEvaluator(), new ContainsEvaluator(),
                new CitationEvaluator(), offlineLlmJudge()));

        assertTrue(registry.byType("exact").isPresent());
        assertTrue(registry.byType("EXACT").isPresent(), "type 匹配大小写不敏感");
        assertTrue(registry.byType("contains").isPresent());
        assertTrue(registry.byType("citation").isPresent());
        assertTrue(registry.byType("llm_judge").isPresent());
        assertTrue(registry.byType("unknown_third_party").isEmpty());
        assertTrue(registry.byType(null).isEmpty());
        assertTrue(registry.byType("").isEmpty());
    }

    // ------------------------------------------------------------------
    // exact
    // ------------------------------------------------------------------

    @Test
    void exactPassesOnIdenticalText() {
        EvalScore s = new ExactEvaluator().score(
                subject("{\"text\":\"回答甲\"}", "{\"text\":\"回答甲\"}", "{\"text\":\"回答甲\"}"),
                Map.of());
        assertEquals(1.0, s.score());
        assertTrue(s.passed());
    }

    @Test
    void exactFailsOnMismatchAndPlainStringExpected() {
        EvalScore s = new ExactEvaluator().score(
                subject("\"expected-answer\"", "\"expected-answer\"", "\"different\""),
                Map.of());
        assertEquals(0.0, s.score());
        assertFalse(s.passed());
    }

    // ------------------------------------------------------------------
    // contains
    // ------------------------------------------------------------------

    @Test
    void containsPassesWhenExpectedInsideActual() {
        EvalScore s = new ContainsEvaluator().score(
                subject("\"q\"", "\"已收到\"", "\"【mock】已收到：q\""),
                Map.of());
        assertEquals(1.0, s.score());
        assertTrue(s.passed());
    }

    @Test
    void containsFailsOnMissingSubstringAndEmptyExpected() {
        EvalScore miss = new ContainsEvaluator().score(
                subject("\"q\"", "\"不存在的片段\"", "\"actual text\""), Map.of());
        assertEquals(0.0, miss.score());
        assertFalse(miss.passed());

        EvalScore empty = new ContainsEvaluator().score(
                subject("\"q\"", "\"\"", "\"actual\""), Map.of());
        assertEquals(0.0, empty.score());
        assertFalse(empty.passed(), "空 expected 不得放行");
    }

    // ------------------------------------------------------------------
    // citation
    // ------------------------------------------------------------------

    @Test
    void citationScoresReferenceHitRatio() {
        // 两条引用命中一条 → score 0.5、不通过
        EvalScore partial = new CitationEvaluator().score(
                subject("\"q\"", "{\"references\":[\"命中A\",\"未命中B\"]}", "\"正文含命中A\""),
                Map.of());
        assertEquals(0.5, partial.score());
        assertFalse(partial.passed());
        assertTrue(partial.reason().contains("未命中B"), "reason 须列出缺失引用");

        EvalScore full = new CitationEvaluator().score(
                subject("\"q\"", "{\"references\":[\"命中A\",\"含命中A\"]}", "\"正文含命中A\""),
                Map.of());
        assertEquals(1.0, full.score());
        assertTrue(full.passed());
    }

    @Test
    void citationFallsBackToExpectedTextAndHandlesNoReferences() {
        EvalScore fallback = new CitationEvaluator().score(
                subject("\"q\"", "\"关键句\"", "\"前缀 关键句 后缀\""), Map.of());
        assertEquals(1.0, fallback.score());
        assertTrue(fallback.passed());

        EvalScore none = new CitationEvaluator().score(
                subject("\"q\"", "null", "\"actual\""), Map.of());
        assertEquals(0.0, none.score());
        assertFalse(none.passed(), "无引用可判定不放行");
    }

    // ------------------------------------------------------------------
    // llm_judge（内存 ModelGateway 替身）
    // ------------------------------------------------------------------

    @Test
    void llmJudgeParsesJsonVerdict() {
        ModelGateway gateway = fakeGateway("{\"score\": 0.87, \"passed\": true, \"reason\": \"基本正确\"}");
        LlmJudgeEvaluator judge = new LlmJudgeEvaluator(
                provider(() -> gateway), emptyHolder(), mapper);
        EvalScore s = judge.score(subject("\"q\"", "\"期望\"", "\"实际\""), Map.of());
        assertEquals(0.87, s.score());
        assertTrue(s.passed());
        assertEquals("基本正确", s.reason());
    }

    @Test
    void llmJudgeToleratesCodeFenceAndDerivesPassedFromScore() {
        ModelGateway gateway = fakeGateway("```json\n{\"score\": 0.95}\n```");
        LlmJudgeEvaluator judge = new LlmJudgeEvaluator(
                provider(() -> gateway), emptyHolder(), mapper);
        EvalScore s = judge.score(subject("\"q\"", "\"期望\"", "\"实际\""), Map.of());
        assertEquals(0.95, s.score());
        assertTrue(s.passed(), "缺省 passed 由 score>=0.6 推导");
    }

    @Test
    void llmJudgeFallsBackOnNonJsonAndMissingGateway() {
        LlmJudgeEvaluator badOutput = new LlmJudgeEvaluator(
                provider(() -> fakeGateway("模型拒绝评分")), emptyHolder(), mapper);
        EvalScore s = badOutput.score(subject("\"q\"", "\"期望\"", "\"实际\""), Map.of());
        assertEquals(0.0, s.score());
        assertFalse(s.passed());
        assertTrue(s.reason().contains("解析失败"));

        LlmJudgeEvaluator absent = new LlmJudgeEvaluator(
                provider(() -> null), emptyHolder(), mapper);
        EvalScore missing = absent.score(subject("\"q\"", "\"期望\"", "\"实际\""), Map.of());
        assertEquals(0.0, missing.score());
        assertFalse(missing.passed());
        assertTrue(missing.reason().contains("模型网关不可用"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private EvalSubject subject(String input, String expected, String actual) {
        return new EvalSubject("case_1", read(input), read(expected), read(actual), "turn_1");
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LlmJudgeEvaluator offlineLlmJudge() {
        return new LlmJudgeEvaluator(provider(() -> null), emptyHolder(), mapper);
    }

    private static ModelGateway fakeGateway(String reply) {
        return new ModelGateway() {
            @Override
            public ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta) {
                return new ChatResult(reply, null, List.of(), 10, 10, "bnd", "gpt-mock", 0, "stop");
            }

            @Override
            public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.tioka.model.api.capability.OcrResult ocr(ModelCallContext ctx,
                    com.tioka.model.api.capability.OcrRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static org.springframework.beans.factory.ObjectProvider<ModelGateway> provider(
            Supplier<ModelGateway> supplier) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public ModelGateway getObject(Object... args) {
                return supplier.get();
            }

            @Override
            public ModelGateway getObject() {
                return supplier.get();
            }

            @Override
            public ModelGateway getIfAvailable() {
                return supplier.get();
            }

            @Override
            public ModelGateway getIfUnique() {
                return supplier.get();
            }

            @Override
            public Stream<ModelGateway> stream() {
                return Stream.empty();
            }
        };
    }

    private static PrincipalHolder emptyHolder() {
        return new PrincipalHolder() {
            @Override
            public Principal current() {
                throw new IllegalStateException("no principal in offline test");
            }

            @Override
            public Optional<Principal> find() {
                return Optional.empty();
            }

            @Override
            public <T> T runAs(Principal principal, Supplier<T> body) {
                return body.get();
            }
        };
    }
}
