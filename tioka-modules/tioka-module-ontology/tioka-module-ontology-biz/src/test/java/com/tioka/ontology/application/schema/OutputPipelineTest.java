package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.ProcessedOutput;
import com.tioka.ontology.api.schema.RawModelOutput;
import com.tioka.ontology.api.schema.ReasonCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-07 验收（ADR-066 §5 第 1〜4 步 + §10.3 异常逃逸）：
 * 完成状态 / 提取 / 严格解析 / 白名单规整，以及「任何输入都恰好得到一个 {@link ProcessedOutput}」。
 */
class OutputPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OutputPipeline pipeline = new OutputPipeline();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode findingSchema() {
        return json("""
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["findingId", "conclusion", "signedOn"],
                  "properties": {
                    "findingId": {"type": "string", "x-tioka-coerce": "number"},
                    "conclusion": {"enum": ["satisfied", "not_satisfied"]},
                    "signedOn": {"type": "string", "x-tioka-coerce": "date"}
                  }
                }""");
    }

    // ==================== 第 1 步：完成状态 ====================

    @Test
    void classifyTruncatedRefusedFilteredEmpty() {
        assertEquals(ReasonCode.TRUNCATED,
                pipeline.classify(new RawModelOutput("{\"a\":1}", true, false, false)));
        assertEquals(ReasonCode.REFUSED,
                pipeline.classify(new RawModelOutput("我不便回答", false, true, false)));
        assertEquals(ReasonCode.CONTENT_FILTERED,
                pipeline.classify(new RawModelOutput("", false, false, true)));
        assertEquals(ReasonCode.EMPTY, pipeline.classify(new RawModelOutput("", false, false, false)));
        assertEquals(ReasonCode.EMPTY, pipeline.classify(new RawModelOutput("   \n", false, false, false)));
        assertEquals(ReasonCode.EMPTY, pipeline.classify(null));
        assertNull(pipeline.classify(RawModelOutput.of("{}")));
    }

    /** 截断优先于内容判定；第 1 步失败即终止，不进入后续步骤（ADR-066 §5）。 */
    @Test
    void classifyPrecedenceAndShortCircuit() {
        ProcessedOutput out = pipeline.process(findingSchema(),
                new RawModelOutput("{\"findingId\":\"F-1\",\"conclusion\":\"satisfied\",\"signedOn\":\"2026-01-01\"}",
                        true, false, false));
        assertFalse(out.ok());
        assertEquals(ReasonCode.TRUNCATED, out.failure());
        assertNull(out.payload());
        // lastCandidate 存档：完整合法的候选输出仍可留档（不可消费）
        assertNotNull(out.lastCandidate());
    }

    // ==================== 第 2 步：提取 ====================

    @Test
    void cleanStripsBomAndSingleFence() {
        String fenced = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", OutputPipeline.clean(fenced));
        assertEquals("{\"a\":1}", OutputPipeline.clean("\uFEFF  {\"a\":1}  "));
        assertEquals("{\"a\":1}", OutputPipeline.clean("{\"a\":1}"));
    }

    @Test
    void locateSingleTopLevelValue() {
        OutputPipeline.Extracted e = OutputPipeline.locate("{\"a\":1}");
        assertNull(e.reason());
        assertEquals("{\"a\":1}", e.json());
        // 说明性文字不计为 JSON 值，唯一顶层值仍是结构体
        OutputPipeline.Extracted prose = OutputPipeline.locate("好的，结果如下：{\"a\":1}");
        assertNull(prose.reason());
        assertEquals("{\"a\":1}", prose.json());
        // 整段本身就是 JSON 标量
        OutputPipeline.Extracted scalar = OutputPipeline.locate("  123  ");
        assertNull(scalar.reason());
        assertEquals("123", scalar.json());
    }

    @Test
    void locateNoJson() {
        assertEquals(ReasonCode.NO_JSON, OutputPipeline.locate("不确定").reason());
        assertEquals(ReasonCode.NO_JSON, OutputPipeline.locate("").reason());
        assertEquals(ReasonCode.NO_JSON, OutputPipeline.locate("   ").reason());
        assertEquals(ReasonCode.NO_JSON, OutputPipeline.locate("这是一段说明，没有 JSON").reason());
        assertEquals(ReasonCode.NO_JSON, OutputPipeline.locate(null).reason());
    }

    @Test
    void locateMultipleJson() {
        assertEquals(ReasonCode.MULTIPLE_JSON, OutputPipeline.locate("{} {}").reason());
        assertEquals(ReasonCode.MULTIPLE_JSON,
                OutputPipeline.locate("{\"a\":1}  {\"b\":2}").reason());
        assertEquals(ReasonCode.MULTIPLE_JSON,
                OutputPipeline.locate("[1] {\"a\":1}").reason());
    }

    /** 未闭合结构体：提取为整段，交由第 3 步报 SYNTAX（而非 NO_JSON）。 */
    @Test
    void locateUnbalancedDelegatesToParse() {
        OutputPipeline.Extracted e = OutputPipeline.locate("{\"a\":1");
        assertNull(e.reason());
        assertEquals(ReasonCode.SYNTAX, pipeline.parse(e.json()).reason());
    }

    // ==================== 第 3 步：严格解析 ====================

    @Test
    void parseRejectsNonStandardJson() {
        assertEquals(ReasonCode.SYNTAX, pipeline.parse("{\"a\":1,}").reason()); // 尾逗号
        assertEquals(ReasonCode.SYNTAX, pipeline.parse("{'a':1}").reason()); // 单引号
        assertEquals(ReasonCode.SYNTAX, pipeline.parse("{\"a\":1 // c\n}").reason()); // 注释
        assertEquals(ReasonCode.SYNTAX, pipeline.parse("{\"a\":NaN}").reason());
        assertEquals(ReasonCode.SYNTAX, pipeline.parse("{\"a\":1} 说明").reason()); // 尾随内容
    }

    @Test
    void parseRejectsDuplicateKeys() {
        assertEquals(ReasonCode.SYNTAX, pipeline.parse("{\"a\":1,\"a\":2}").reason());
    }

    @Test
    void parseRejectsTooDeepNesting() {
        String deep = "[".repeat(OutputPipeline.MAX_DEPTH + 8) + "]".repeat(OutputPipeline.MAX_DEPTH + 8);
        assertEquals(ReasonCode.SYNTAX, pipeline.parse(deep).reason());
    }

    @Test
    void parseRejectsOversizedString() {
        String big = "{\"a\":\"" + "x".repeat(OutputPipeline.MAX_STRING_LENGTH + 1) + "\"}";
        assertEquals(ReasonCode.SYNTAX, pipeline.parse(big).reason());
    }

    @Test
    void parseKeepsDecimalPrecision() {
        OutputPipeline.Parsed p = pipeline.parse("{\"amount\":123456789012345678901234567890.123456789}");
        assertNull(p.reason());
        // 十进制精确值：不经 double 往返
        assertTrue(p.node().get("amount").isBigDecimal() || p.node().get("amount").isBigInteger());
        assertEquals("123456789012345678901234567890.123456789", p.node().get("amount").asText());
    }

    // ==================== 第 4 步：白名单规整 ====================

    @Test
    void normalizeEnumCaseInsensitiveUniqueMatch() {
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"c\":{\"enum\":[\"satisfied\",\"not_satisfied\"]}}}");
        OutputPipeline.Normalized n = pipeline.normalize(schema, json("{\"c\":\"SATISFIED\"}"));
        assertEquals("satisfied", n.payload().get("c").asText());
        assertEquals(List.of("/c: enum->satisfied"), n.notes());
    }

    /** 两个候选只差大小写 → 不唯一 → 不猜测。 */
    @Test
    void normalizeEnumAmbiguousNotGuessed() {
        assertNull(OutputPipeline.enumCanonical(json("[\"Ab\",\"aB\"]"), "ab"));
    }

    @Test
    void normalizeTrimsStringsAndCoercesNumberAndDate() {
        OutputPipeline.Normalized n = pipeline.normalize(findingSchema(), json("""
                {"findingId":" 12345 ","conclusion":"Satisfied","signedOn":"2026年3月7日"}"""));
        assertEquals("12345", n.payload().get("findingId").asText());
        assertEquals("satisfied", n.payload().get("conclusion").asText());
        assertEquals("2026-03-07", n.payload().get("signedOn").asText());
        assertTrue(n.notes().contains("/signedOn: coerce date"));
    }

    @Test
    void coerceDateSlashFormAndInvalidRejected() {
        assertEquals("2026-03-07", OutputPipeline.coerceDate("2026/3/7"));
        assertNull(OutputPipeline.coerceDate("2026-03-07"));
        assertNull(OutputPipeline.coerceDate("2026年13月7日"));
        assertEquals("1234.5", OutputPipeline.coerceNumber(" 1,234.5 ").asText());
        assertEquals("1234", OutputPipeline.coerceNumber("1234").asText());
        assertNull(OutputPipeline.coerceNumber("约一千二百"));
        // 中文数字不转换（ADR-066 §5 第 4 步禁止项）
        assertNull(OutputPipeline.coerceDate("二〇二六年三月七日"));
    }

    /** 禁止项：不补缺失字段、不截断超长字符串、不产生额外字段。 */
    @Test
    void normalizeNeverAddsOrDropsFields() {
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}}}");
        OutputPipeline.Normalized n = pipeline.normalize(schema, json("{\"a\":\"x\",\"extra\":1}"));
        assertFalse(n.payload().has("b"));
        assertTrue(n.payload().has("extra"));
        assertEquals(2, n.payload().size());
    }

    // ==================== 编排 + 异常逃逸（ADR-066 §10.3） ====================

    @Test
    void processHappyPath() {
        ProcessedOutput out = pipeline.process(findingSchema(), RawModelOutput.of(
                "```json\n{\"findingId\":\" F-9 \",\"conclusion\":\"Satisfied\",\"signedOn\":\"2026/3/7\"}\n```"));
        assertTrue(out.ok());
        assertEquals("F-9", out.payload().get("findingId").asText());
        assertEquals("satisfied", out.payload().get("conclusion").asText());
        assertEquals("2026-03-07", out.payload().get("signedOn").asText());
        assertTrue(out.normalizations().size() >= 2);
    }

    /** 变异测试：每个输入都恰好得到一个 {@link ProcessedOutput}，无异常逃逸（零逃逸）。 */
    @Test
    void mutationInputsNeverEscape() {
        List<String> mutations = List.of(
                "",
                "   ",
                "不确定",
                "{} {}",
                "{\"a\":1}  {\"b\":2}",
                "{",
                "{\"a\":}",
                "{\"a\":1,}",
                "{'a':'b'}",
                "{\"a\":1 /* c */}",
                "{\"a\":1,\"a\":2}",
                "[".repeat(60) + "]".repeat(60),
                "{\"a\":\"" + "x".repeat(70 * 1024) + "\"}",
                "{\"a\":NaN}",
                "{\"a\":1e999999}",
                "{\"amount\":123456789012345678901234567890123456789012345678901234567890}",
                "\"\\uD800\"",
                "{\"a\":{\"b\":{\"c\":[[[[]]]]}}}",
                "null",
                "[1,2,3]",
                "{\"findingId\":123,\"conclusion\":\"x\",\"signedOn\":\"y\"}",
                "{\"findingId\":\"F\",\"signedOn\":\"2026-01-01\"}",
                "{\"findingId\":\"F\",\"conclusion\":\"satisfied\",\"signedOn\":\"2026-01-01\",\"extra\":1}"
        );
        RawModelOutput truncated = new RawModelOutput("{\"findingId\":\"F\",\"conclusion\":\"sat", true, false, false);
        List<RawModelOutput> candidates = new java.util.ArrayList<>();
        for (String m : mutations) {
            candidates.add(RawModelOutput.of(m));
        }
        candidates.add(truncated);
        candidates.add(new RawModelOutput("", false, true, false));
        candidates.add(new RawModelOutput("", false, false, true));
        candidates.add(null);

        for (RawModelOutput raw : candidates) {
            ProcessedOutput out = assertDoesNotThrow(() -> pipeline.process(findingSchema(), raw),
                    () -> "异常逃逸：" + raw);
            assertNotNull(out, () -> "必须返回 ProcessedOutput：" + raw);
            if (out.ok()) {
                assertNotNull(out.payload());
                assertFalse(out.payload().isMissingNode());
                assertNull(out.failure());
                // 通过也不得交付部分对象：载荷必须是可独立序列化的完整对象
                assertDoesNotThrow(() -> MAPPER.writeValueAsString(out.payload()));
            } else {
                assertNull(out.payload());
                assertNotNull(out.failure());
                assertFalse(out.errors().isEmpty());
            }
        }
    }

    /** 合法 payload 的规整是确定性的（同输入同输出）。 */
    @Test
    void normalizationIsDeterministic() {
        RawModelOutput raw = RawModelOutput.of("{\"findingId\":\"F\",\"conclusion\":\"NOT_SATISFIED\",\"signedOn\":\"2026/1/2\"}");
        ProcessedOutput a = pipeline.process(findingSchema(), raw);
        ProcessedOutput b = pipeline.process(findingSchema(), raw);
        assertEquals(a.payload().toString(), b.payload().toString());
        assertEquals(a.normalizations(), b.normalizations());
    }
}
