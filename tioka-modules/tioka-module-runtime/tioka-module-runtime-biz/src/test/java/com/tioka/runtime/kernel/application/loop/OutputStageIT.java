package com.tioka.runtime.kernel.application.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.FallbackStep;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.agent.api.spec.OutputMode;
import com.tioka.agent.api.spec.OutputStrategy;
import com.tioka.ontology.application.schema.EvidenceBinder;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.ontology.domain.TextNormalizer;
import com.tioka.ontology.infrastructure.DocumentRepository;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.domain.AgentLoop;
import com.tioka.runtime.kernel.domain.ContextBuilder;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.domain.LoopResult;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-02/SCH-03 验收（M3-03 §5 测试清单，ADR-066 §6 修复语义）：
 * repair / exhausted / fakeQuote / toolMode。离线：InMemory schema store + 桩 ModelPort。
 */
class OutputStageIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_REF = "lease.finding@1";

    private SchemaRegistry registry;
    private InMemoryItems items;
    private ScriptedModelPort modelPort;
    private RecordingSink sink;
    private OutputStage stage;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry(MAPPER);
        registry.publish("lease.finding", 1, findingSchema(), "test");
        items = new InMemoryItems();
        modelPort = new ScriptedModelPort();
        sink = new RecordingSink();
        stage = newStage("v-final");
    }

    private OutputStage newStage(String contractVersion) {
        Function<String, OutputContract> contracts =
                versionId -> versionId == null ? null : contractsByVersion.get(versionId);
        return new OutputStage(MAPPER, contracts, registry, modelPort,
                new ContextBuilder(MAPPER), items::list, sink);
    }

    private final Map<String, OutputContract> contractsByVersion = Map.of(
            "v-final", new OutputContract(SCHEMA_REF, OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null, false),
            "v-evidence", new OutputContract(SCHEMA_REF, OutputMode.FINAL, 2, true, OutputStrategy.DIRECT, null, false),
            "v-tool", new OutputContract(SCHEMA_REF, OutputMode.TOOL, 2, false, OutputStrategy.DIRECT, null, false));

    // ---- schema 与 payload ----

    private static JsonNode findingSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "x-tioka-evidence": "required",
                      "additionalProperties": false,
                      "required": ["findingId", "conclusion"],
                      "properties": {
                        "findingId": {"type": "string"},
                        "conclusion": {"enum": ["SATISFIED", "NOT_SATISFIED"]},
                        "evidence": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "required": ["unitId", "quote"],
                            "properties": {
                              "unitId": {"type": "string"},
                              "quote": {"type": "string"}
                            }
                          }
                        }
                      }
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payload(String findingId, String conclusion, String unitId, String quote) {
        String evidence = unitId == null ? ""
                : """
                ,"evidence":[{"unitId":"%s","quote":"%s"}]""".formatted(unitId, quote.replace("\"", "\\\""));
        return "{\"findingId\":\"%s\",\"conclusion\":\"%s\"%s}".formatted(findingId, conclusion, evidence);
    }

    // ---- 桩 ----

    /** 内存 item 列表：sink 写入的 item 对 ContextBuilder / visibleDocIds 聚合可见。 */
    private static final class InMemoryItems {
        final List<RuntimeItemDO> list = new ArrayList<>();

        List<RuntimeItemDO> list(String threadId) {
            return list;
        }

        RuntimeItemDO append(String type, String role, String contentJson, String turnId) {
            RuntimeItemDO item = new RuntimeItemDO();
            item.setId("itm-" + (list.size() + 1));
            item.setThreadId("th1");
            item.setTurnId(turnId);
            item.setSeq((long) (list.size() + 1));
            item.setStep(0);
            item.setType(type);
            item.setRole(role);
            item.setContentJson(contentJson);
            list.add(item);
            return item;
        }
    }

    /** 脚本化模型：按调用次序返回预设步（含 toolCalls）；记录每次调用的消息与结构化需求。 */
    private static final class ScriptedModelPort implements LoopPorts.ModelPort {
        static final class Step {
            final String text;
            final List<LoopPorts.ToolCall> toolCalls;
            final int completionTokens;

            Step(String text, List<LoopPorts.ToolCall> toolCalls, int completionTokens) {
                this.text = text;
                this.toolCalls = toolCalls == null ? List.of() : toolCalls;
                this.completionTokens = completionTokens;
            }
        }

        final List<Step> script = new ArrayList<>();
        /** 首次输出在 FINAL 测试中手工构造（不经桩），修复轮从该下标开始消费。 */
        int startIndex = 0;
        final AtomicInteger calls = new AtomicInteger();
        final List<List<Map<String, Object>>> seenMessages = new ArrayList<>();
        final List<com.tioka.model.api.constraint.StructuredDemand> seenDemands = new ArrayList<>();

        @Override
        public Result chat(LoopContext ctx, List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools, Consumer<Delta> onDelta) {
            return chat(ctx, messages, tools, onDelta, null);
        }

        @Override
        public Result chat(LoopContext ctx, List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools, Consumer<Delta> onDelta,
                           com.tioka.model.api.constraint.StructuredDemand structured) {
            int i = startIndex + calls.getAndIncrement();
            seenMessages.add(messages);
            seenDemands.add(structured);
            if (i >= script.size()) {
                throw new IllegalStateException("脚本用尽：第 " + i + " 次调用");
            }
            Step s = script.get(i);
            return new Result(s.text, null, s.toolCalls, 10, s.completionTokens, "mock");
        }
    }

    /** 记录型 sink：complete 写入内存 item 列表，供修复轮消息重建。 */
    private final class RecordingSink implements LoopPorts.ItemSink {
        @Override
        public void delta(LoopContext ctx, LoopPorts.ModelPort.Delta delta) {}

        @Override
        public void completeMessage(LoopContext ctx, int step, String text) {
            items.append(ItemType.MESSAGE.wire(), "assistant", json(Map.of("text", text)), ctx.turnId());
        }

        @Override
        public void complete(LoopContext ctx, int step, String type, String role, String contentJson) {
            items.append(type, role, contentJson, ctx.turnId());
        }

        @Override
        public String toolCall(LoopContext ctx, int step, LoopPorts.ToolCall call) {
            items.append(ItemType.TOOL_CALL.wire(), "assistant",
                    json(Map.of("callId", call.callId(), "name", call.name(),
                            "arguments", parse(call.argumentsJson()))), ctx.turnId());
            return "itm-call";
        }

        @Override
        public void toolResult(LoopContext ctx, int step, LoopPorts.ToolOutcome outcome) {
            items.append(ItemType.TOOL_RESULT.wire(), "tool",
                    json(Map.of("callId", outcome.callId(), "name", outcome.name(),
                            "ok", outcome.ok(), "output", String.valueOf(outcome.output()))), ctx.turnId());
        }

        @Override
        public void status(LoopContext ctx, int step, String kind) {
            items.append(ItemType.STATUS.wire(), "system", json(Map.of("kind", kind)), ctx.turnId());
        }

        private Object parse(String raw) {
            try {
                return MAPPER.readTree(raw);
            } catch (Exception e) {
                return raw;
            }
        }
    }

    private LoopContext ctx() {
        return ctx("v-final");
    }

    private LoopContext ctx(String agentVersionId) {
        return new LoopContext("th1", "turn-1", 1, 20, 3, 0, c -> {},
                agentVersionId, null, "ws1");
    }

    private String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> dataItems() {
        return items.list.stream()
                .filter(i -> ItemType.DATA.wire().equals(i.getType()))
                .map(i -> readMap(i.getContentJson()))
                .toList();
    }

    private List<RuntimeItemDO> itemsOfType(String type) {
        return items.list.stream().filter(i -> type.equals(i.getType())).toList();
    }

    // ---- OutputStageIT#repair：第一次漏 required 字段，第二次补上 → valid=true, repairRounds=1 ----

    @Test
    void repair() {
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "F-1")), null, 50)); // 缺 conclusion
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", null, null), null, 40));

        modelPort.startIndex = 1; // 首次输出手工构造，修复轮从 script[1] 消费
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(modelPort.script.get(0).text, null,
                        List.of(), 10, 50, "mock"),
                10, 50);

        assertNotNull(result);
        assertNull(result.errorCode());
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.TRUE, data.get(0).get("valid"));
        assertEquals(1, ((Number) data.get(0).get("repairRounds")).intValue());
        assertEquals(1, modelPort.calls.get()); // 首次输出手工构造，仅 1 次修复轮经桩
        // 修复轮带结构化需求（同方言同生成方式：同 StructuredDemand）
        assertNotNull(modelPort.seenDemands.get(0));
        assertFalse(modelPort.seenDemands.get(0).emitAsTool());
        assertEquals(SCHEMA_REF, modelPort.seenDemands.get(0).schemaRef());
        // 修复消息只给错误不给答案（错误清单，不含修正后的 payload）
        String repairMessage = itemsOfType(ItemType.MESSAGE.wire()).stream()
                .map(RuntimeItemDO::getContentJson)
                .filter(c -> c.contains("SCHEMA_VIOLATION"))
                .findFirst().orElse(null);
        assertNotNull(repairMessage, "修复轮应有错误清单消息");
        assertFalse(repairMessage.contains("SATISFIED"), "不给正确答案提示");
    }

    // ---- OutputStageIT#exhausted：每轮错误都不同 → 轮数用尽 → valid=false + COMPLETED(output_invalid) ----

    @Test
    void exhausted() {
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "F-1")), null, 50)); // 缺 conclusion
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("conclusion", "SATISFIED")), null, 50)); // 缺 findingId
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", 1, "conclusion", "SATISFIED")), null, 50)); // 类型错
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", 2, "conclusion", "SATISFIED")), null, 50)); // 不应被消费

        modelPort.startIndex = 1;
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(modelPort.script.get(0).text, null,
                        List.of(), 10, 50, "mock"),
                10, 50);

        assertEquals("output_invalid", result.errorCode());
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.FALSE, data.get(0).get("valid"));
        assertEquals("FAILED", data.get(0).get("outcome"));
        assertTrue(((List<?>) data.get(0).get("errors")).size() > 0);
        assertEquals(2, modelPort.calls.get()); // 首次手工构造 + maxRepair=2 轮中 2 次（第 3 个脚本步不应被消费）
    }

    /** 早停：错误集合与上一轮完全相同 → 立即停止（不耗尽 maxRepair）。 */
    @Test
    void earlyStopOnIdenticalErrors() {
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "F-1")), null, 50)); // 缺 conclusion
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "F-1")), null, 50)); // 同样的错误
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", null, null), null, 50)); // 不应被消费

        modelPort.startIndex = 1;
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(modelPort.script.get(0).text, null,
                        List.of(), 10, 50, "mock"),
                10, 50);

        assertEquals("output_invalid", result.errorCode());
        assertEquals(1, modelPort.calls.get()); // 早停：1 次修复轮后错误集合与上轮相同即停
        assertEquals(Boolean.FALSE, dataItems().get(0).get("valid"));
    }

    /** maxRepairTokens：首次输出 token 的 2 倍，先到为准（此处首次 50 → 预算 100，两轮修复 60+50 超限前按轮数停）。 */
    @Test
    void repairTokenBudgetStopsBeforeExhaustion() {
        // 首次 10 tokens → 预算 20；第一轮修复 20 tokens 后预算用尽 → 不再发起第二轮
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "F-1")), null, 10));
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "G-1")), null, 20)); // 不同的错误，不会早停
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", null, null), null, 10)); // 不应被消费

        modelPort.startIndex = 1;
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(modelPort.script.get(0).text, null,
                        List.of(), 10, 10, "mock"),
                10, 10);

        assertEquals("output_invalid", result.errorCode());
        assertEquals(1, modelPort.calls.get()); // 修复轮 token 用尽（20 ≥ 预算 20），不再发起第二轮
        assertEquals(Boolean.FALSE, dataItems().get(0).get("valid"));
    }

    // ---- OutputStageIT#fakeQuote：引文改一个字 → QUOTE_MISMATCH → 进入修复而非直接失败 ----

    @Test
    void fakeQuote() {
        SchemaRegistry withEvidence = new SchemaRegistry(MAPPER, new ObjectProviderStub(binderWithLeaseUnit()));
        withEvidence.publish("lease.finding", 1, findingSchema(), "test");
        Function<String, OutputContract> contracts =
                versionId -> versionId == null ? null : contractsByVersion.get("v-evidence");
        stage = new OutputStage(MAPPER, contracts, withEvidence, modelPort,
                new ContextBuilder(MAPPER), items::list, sink);
        // visibleDocIds 最小来源：本 Turn 的 doc.unit 调用使 doc1 可见
        sink.toolCall(ctx(), 0, new LoopPorts.ToolCall("c1", "doc.unit",
                json(Map.of("unitId", "doc1:1:1"))));

        // 第一次：引文改一个字（「三个月」→「四个月」）
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", "doc1:1:1", "免租期为四个月"), null, 50));
        // 修复轮：正确引文
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", "doc1:1:1", "免租期为三个月"), null, 40));

        modelPort.startIndex = 1;
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(modelPort.script.get(0).text, null,
                        List.of(), 10, 50, "mock"),
                10, 50);

        // 进入修复而非直接失败：修复消息携带 QUOTE_MISMATCH
        String repairMessage = itemsOfType(ItemType.MESSAGE.wire()).stream()
                .map(RuntimeItemDO::getContentJson)
                .filter(c -> c.contains("QUOTE_MISMATCH"))
                .findFirst().orElse(null);
        assertNotNull(repairMessage, "伪造引文应以 QUOTE_MISMATCH 进入修复");
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.TRUE, data.get(0).get("valid"));
        assertEquals(1, ((Number) data.get(0).get("repairRounds")).intValue());
    }

    private EvidenceBinder binderWithLeaseUnit() {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        DocumentRepository.UnitRow u = new DocumentRepository.UnitRow();
        u.unitId = "doc1:1:1";
        u.docId = "doc1";
        u.pageNo = 1;
        u.seq = 1;
        u.kind = "PARA";
        u.text = "免租期为三个月。";
        u.normText = TextNormalizer.normalize("免租期为三个月。");
        DocumentRepository.DocumentRow doc = new DocumentRepository.DocumentRow(
                "doc1", "hash-doc1", "1.0.0", "application/pdf", "doc1.pdf",
                null, null, "k/doc1", null, "ws1");
        doc.parseStatus = "PENDING";
        repository.persistParsed(doc,
                List.of(new DocumentRepository.PageRow("doc1", 1, "TEXT", "text", 100.0, null)),
                List.of(u), List.of());
        return new EvidenceBinder(repository);
    }

    private static final class ObjectProviderStub
            implements org.springframework.beans.factory.ObjectProvider<EvidenceBinder> {
        private final EvidenceBinder binder;

        ObjectProviderStub(EvidenceBinder binder) {
            this.binder = binder;
        }

        @Override public EvidenceBinder getObject() { return binder; }
        @Override public EvidenceBinder getObject(Object... args) { return binder; }
        @Override public EvidenceBinder getIfAvailable() { return binder; }
        @Override public EvidenceBinder getIfUnique() { return binder; }
    }

    // ---- mode=TOOL：emit_output 校验往返（经 AgentLoop），汇总有效者为最终输出 ----

    @Test
    void toolModeRepairRoundTrip() {
        OutputStage toolStage = newStage("v-tool");
        AgentLoop loop = new AgentLoop(modelPort, emptyTools(), sink,
                new ContextBuilder(MAPPER), org.mockito.Mockito.mock(TurnRepository.class),
                items::list, MAPPER, null, null, toolStage);

        modelPort.script.add(new ScriptedModelPort.Step(null, List.of(
                new LoopPorts.ToolCall("call-1", OutputStage.EMIT_OUTPUT,
                        json(Map.of("findingId", "F-1")))), 20)); // 缺 conclusion → 失败
        modelPort.script.add(new ScriptedModelPort.Step(null, List.of(
                new LoopPorts.ToolCall("call-2", OutputStage.EMIT_OUTPUT,
                        payload("F-1", "SATISFIED", null, null))), 20)); // 合法
        modelPort.script.add(new ScriptedModelPort.Step("done", null, 20));

        LoopResult result = loop.run(ctx("v-tool"));

        assertNotNull(result);
        assertNull(result.errorCode());
        // 汇总：有效者为最终输出，仅写一个 data item
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.TRUE, data.get(0).get("valid"));
        assertEquals(payload("F-1", "SATISFIED", null, null),
                MAPPER.valueToTree(data.get(0).get("payload")).toString());
        // 校验往返：第一次失败（错误清单交回模型自修），第二次成功
        List<RuntimeItemDO> toolResults = itemsOfType(ItemType.TOOL_RESULT.wire());
        assertEquals(2, toolResults.size());
        assertEquals(Boolean.FALSE, readMap(toolResults.get(0).getContentJson()).get("ok"));
        assertTrue(((String) readMap(toolResults.get(0).getContentJson()).get("output"))
                .contains("SCHEMA_VIOLATION"));
        assertEquals(Boolean.TRUE, readMap(toolResults.get(1).getContentJson()).get("ok"));
    }

    /** TOOL 模式全部 emit_output 都无效 → valid=false + output_invalid。 */
    @Test
    void toolModeAllInvalid() {
        OutputStage toolStage = newStage("v-tool");
        AgentLoop loop = new AgentLoop(modelPort, emptyTools(), sink,
                new ContextBuilder(MAPPER), org.mockito.Mockito.mock(TurnRepository.class),
                items::list, MAPPER, null, null, toolStage);

        modelPort.script.add(new ScriptedModelPort.Step(null, List.of(
                new LoopPorts.ToolCall("call-1", OutputStage.EMIT_OUTPUT,
                        json(Map.of("findingId", "F-1")))), 20));
        modelPort.script.add(new ScriptedModelPort.Step("放弃使用工具", null, 20));

        LoopResult result = loop.run(ctx("v-tool"));

        assertEquals("output_invalid", result.errorCode());
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.FALSE, data.get(0).get("valid"));
        assertEquals(0, itemsOfType(ItemType.TOOL_RESULT.wire()).stream()
                .filter(i -> Boolean.TRUE.equals(readMap(i.getContentJson()).get("ok")))
                .count());
    }

    private static LoopPorts.ToolPort emptyTools() {
        return new LoopPorts.ToolPort() {
            @Override
            public List<Map<String, Object>> describe(LoopContext ctx) {
                return List.of();
            }

            @Override
            public List<LoopPorts.ToolOutcome> invokeAll(LoopContext ctx, List<LoopPorts.ToolCall> calls) {
                return List.of();
            }
        };
    }

    // ---- 直通：无 output 契约时行为与现状一致 ----

    @Test
    void passthroughWithoutContract() {
        LoopContext noContractCtx = new LoopContext("th1", "turn-2", 1, 20, 3, 0, c -> {});
        assertNull(stage.onLoopExit(noContractCtx, 0,
                new LoopPorts.ModelPort.Result("hi", null, List.of(), 1, 1, "mock"), 1, 1));
        assertNull(stage.structuredDemand(noContractCtx));
        assertFalse(stage.toolMode(noContractCtx));
    }

    /** visibleDocIds 最小来源：本 Turn 的 doc.unit 调用 → 可见文档清单。 */
    @Test
    void visibleDocIdsFromDocUnitCalls() {
        // SinkToolCall 写入 TOOL_CALL item（turn-1），unitId 形如 {docId}:{pageNo}:{seq}
        sink.toolCall(ctx(), 0, new LoopPorts.ToolCall("c1", "doc.unit",
                json(Map.of("unitId", "docA:1:3"))));
        sink.toolCall(ctx(), 0, new LoopPorts.ToolCall("c2", "doc.unit",
                json(Map.of("unitId", "docB:2:7"))));
        List<String> visible = stage.visibleDocIds(ctx());
        assertEquals(List.of("docA", "docB"), visible);
    }

    // ---- SCH-07：截断不进入修复；四结局与原因码落 data item ----

    /** 第 1 步完成状态：finish_reason=length → TRUNCATED，直接判失败，不发起修复调用。 */
    @Test
    void truncatedDoesNotEnterRepair() {
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", null, null), null, 50)); // 不应被消费
        modelPort.startIndex = 1;
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(payload("F-1", "SATISFIED", null, null), null,
                        List.of(), 10, 50, "mock", "length"),
                10, 50);

        assertEquals("output_invalid", result.errorCode());
        assertEquals(0, modelPort.calls.get()); // 截断不进入修复
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals("FAILED", data.get(0).get("outcome"));
        assertEquals("TRUNCATED", data.get(0).get("reasonCode"));
        assertEquals(Boolean.FALSE, data.get(0).get("valid"));
        assertNull(data.get(0).get("payload")); // 不交付部分对象
        assertNotNull(data.get(0).get("lastCandidate")); // 候选存档（不可消费）
    }

    /** 第 1 步完成状态：拒答信号（refused=true，模拟 message.refusal）→ REFUSED，直接判失败不修复。 */
    @Test
    void refusedDoesNotEnterRepair() {
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", null, null), null, 50)); // 不应被消费
        modelPort.startIndex = 1;
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result("抱歉，我不能协助该请求。", null,
                        List.of(), 10, 50, "mock", "stop", true),
                10, 50);

        assertEquals("output_invalid", result.errorCode());
        assertEquals(0, modelPort.calls.get()); // 拒答不进入修复
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals("FAILED", data.get(0).get("outcome"));
        assertEquals("REFUSED", data.get(0).get("reasonCode"));
        assertEquals(Boolean.FALSE, data.get(0).get("valid"));
        assertNull(data.get(0).get("payload")); // 不交付部分对象
    }

    /** 成功路径落四结局与 producedBy：一次即通过 → VALID、无原因码、零修复轮。 */
    @Test
    void validOutcomeCarriesReasonAndProducedBy() {
        LoopContext ctx = ctx();
        LoopResult result = stage.onLoopExit(ctx, 0,
                new LoopPorts.ModelPort.Result(payload("F-1", "SATISFIED", null, null), null,
                        List.of(), 10, 50, "mock", "stop"),
                10, 50);

        assertNull(result.errorCode());
        assertEquals(0, modelPort.calls.get()); // 一次即通过，无修复调用
        Map<String, Object> data = dataItems().get(0);
        assertEquals("VALID", data.get("outcome"));
        assertEquals(Boolean.TRUE, data.get("valid"));
        assertNull(data.get("reasonCode"));
        assertEquals(List.of(), data.get("unknownPaths"));
        assertEquals(0, ((Number) data.get("repairRounds")).intValue());
        assertNull(data.get("lastCandidate")); // 成功不携带候选存档
        Map<String, Object> producedBy = producedBy(data);
        assertEquals("mock", producedBy.get("model"));
        assertEquals(0, ((Number) producedBy.get("repairRounds")).intValue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> producedBy(Map<String, Object> data) {
        return (Map<String, Object>) data.get("producedBy");
    }

    /** 第 8 步领域钩子/WARN：语义规则 WARN 不阻断，结局为 VALID_WITH_UNKNOWN 且记 unknownPaths。 */
    @Test
    void warnRuleYieldsValidWithUnknown() {
        registry.publish("lease.warn", 1, warnSchema(), "test");
        Map<String, OutputContract> byVersion = Map.of("v-warn",
                new OutputContract("lease.warn@1", OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null, false));
        OutputStage warnStage = new OutputStage(MAPPER,
                versionId -> versionId == null ? null : byVersion.get(versionId),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink);

        LoopResult result = warnStage.onLoopExit(ctx("v-warn"), 0,
                new LoopPorts.ModelPort.Result("{\"a\":1,\"b\":5}", null,
                        List.of(), 10, 10, "mock", "stop"),
                10, 10);

        assertNull(result.errorCode());
        Map<String, Object> data = dataItems().get(0);
        assertEquals("VALID_WITH_UNKNOWN", data.get("outcome"));
        assertEquals(Boolean.TRUE, data.get("valid"));
        assertEquals(List.of("#r-warn"), data.get("unknownPaths"));
    }

    private static JsonNode warnSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["a", "b"],
                      "properties": {
                        "a": {"type": "integer"},
                        "b": {"type": "integer"}
                      },
                      "x-tioka-rules": [
                        {"id": "r-warn", "expr": "self.a > self.b",
                         "message": "建议人工复核", "severity": "WARN"}
                      ]
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- SCH-08：x-tioka-rules 与 x-tioka-ref（ADR-066 §5.1/§5.2） ----

    /** §5.1：规则不满足（ERROR）进入修复而非直接失败；M3-00 验收「起租日晚于到期日被拒并进入修复」。 */
    @Test
    void ruleViolationEntersRepair() {
        registry.publish("lease.period", 1, periodSchema(), "test");
        OutputStage periodStage = stageFor("v-period", "lease.period@1");

        // 首次：到期日早于起租日 → 规则不满足
        modelPort.script.add(new ScriptedModelPort.Step(
                "{\"startDate\":\"2026-01-01\",\"endDate\":\"2025-01-01\"}", null, 50));
        // 修复轮：到期日晚于起租日 → 通过
        modelPort.script.add(new ScriptedModelPort.Step(
                "{\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\"}", null, 40));

        modelPort.startIndex = 1;
        LoopResult result = periodStage.onLoopExit(ctx("v-period"), 0,
                new LoopPorts.ModelPort.Result(
                        "{\"startDate\":\"2026-01-01\",\"endDate\":\"2025-01-01\"}",
                        null, List.of(), 10, 50, "mock"),
                10, 50);

        assertNull(result.errorCode());
        // 进入修复：修复消息为规则 message，且原因码为 RULE
        String repairMessage = itemsOfType(ItemType.MESSAGE.wire()).stream()
                .map(RuntimeItemDO::getContentJson)
                .filter(c -> c.contains("到期日不早于起租日"))
                .findFirst().orElse(null);
        assertNotNull(repairMessage, "规则不满足应以 RULE 进入修复");
        assertTrue(repairMessage.contains("RULE"));
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.TRUE, data.get(0).get("valid"));
        assertEquals(1, ((Number) data.get(0).get("repairRounds")).intValue());
    }

    /** §5.2：引用不存在（REF_NOT_FOUND）进入修复；可见单元引用通过。 */
    @Test
    void refNotFoundEntersRepair() {
        SchemaRegistry withRefs = new SchemaRegistry(MAPPER, new ObjectProviderStub(binderWithLeaseUnit()));
        withRefs.publish("lease.ref", 1, refSchema(), "test");
        Map<String, OutputContract> byVersion = Map.of("v-ref",
                new OutputContract("lease.ref@1", OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null, false));
        OutputStage refStage = new OutputStage(MAPPER,
                versionId -> versionId == null ? null : byVersion.get(versionId),
                withRefs, modelPort, new ContextBuilder(MAPPER), items::list, sink);
        // binder.exists 需 doc1 在可见清单内：本 Turn 的 doc.unit 调用使 doc1 可见
        sink.toolCall(ctx(), 0, new LoopPorts.ToolCall("c1", "doc.unit",
                json(Map.of("unitId", "doc1:1:1"))));

        // 首次：引用不存在的单元 → REF_NOT_FOUND
        modelPort.script.add(new ScriptedModelPort.Step("{\"unitRef\":\"ghost:1:1\"}", null, 50));
        // 修复轮：引用存在且可见的单元 → 通过
        modelPort.script.add(new ScriptedModelPort.Step("{\"unitRef\":\"doc1:1:1\"}", null, 40));

        modelPort.startIndex = 1;
        LoopResult result = refStage.onLoopExit(ctx("v-ref"), 0,
                new LoopPorts.ModelPort.Result("{\"unitRef\":\"ghost:1:1\"}", null,
                        List.of(), 10, 50, "mock"),
                10, 50);

        assertNull(result.errorCode());
        String repairMessage = itemsOfType(ItemType.MESSAGE.wire()).stream()
                .map(RuntimeItemDO::getContentJson)
                .filter(c -> c.contains("REF_NOT_FOUND"))
                .findFirst().orElse(null);
        assertNotNull(repairMessage, "引用不存在应以 REF_NOT_FOUND 进入修复");
        List<Map<String, Object>> data = dataItems();
        assertEquals(1, data.size());
        assertEquals(Boolean.TRUE, data.get(0).get("valid"));
        assertEquals(1, ((Number) data.get(0).get("repairRounds")).intValue());
    }

    /** 单契约的 OutputStage（复用同一 registry/桩）。 */
    private OutputStage stageFor(String versionId, String schemaRef) {
        Map<String, OutputContract> byVersion = Map.of(versionId,
                new OutputContract(schemaRef, OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null, false));
        return new OutputStage(MAPPER,
                v -> v == null ? null : byVersion.get(v),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink);
    }

    private static JsonNode periodSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["startDate", "endDate"],
                      "properties": {
                        "startDate": {"type": "string", "description": "起租日"},
                        "endDate": {"type": "string", "description": "到期日"}
                      },
                      "x-tioka-rules": [
                        {"id": "lease.period", "expr": "self.endDate >= self.startDate",
                         "message": "到期日不早于起租日", "severity": "ERROR", "hint": true}
                      ]
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode refSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["unitRef"],
                      "properties": {
                        "unitRef": {"type": "string", "description": "单元", "x-tioka-ref": "doc.unit"}
                      }
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- SCH-09：降级阶梯 / 兜底值 / 人工补录（ADR-066 §7.1〜§7.4） ----

    /** 降级阶梯足迹：修复用尽 → FAIL；producedBy.fallbackSteps 记录每步（§7.1）。 */
    @Test
    void fallbackLadderRecordsFootprint() {
        Map<String, OutputContract> byVersion = Map.of("v-ladder", new OutputContract(
                SCHEMA_REF, OutputMode.FINAL, 1, false, OutputStrategy.DIRECT, null, false,
                List.of(FallbackStep.REPAIR, FallbackStep.FAIL)));
        OutputStage ladderStage = new OutputStage(MAPPER,
                v -> v == null ? null : byVersion.get(v),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink);

        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("findingId", "F-1")), null, 50));       // 缺 conclusion
        modelPort.script.add(new ScriptedModelPort.Step(
                json(Map.of("conclusion", "SATISFIED")), null, 50)); // 缺 findingId（不同错误，不早停）
        modelPort.startIndex = 1;

        LoopResult result = ladderStage.onLoopExit(ctx("v-ladder"), 0,
                new LoopPorts.ModelPort.Result(modelPort.script.get(0).text, null,
                        List.of(), 10, 50, "mock"), 10, 50);

        assertEquals("output_invalid", result.errorCode());
        Map<String, Object> data = dataItems().get(0);
        assertEquals("FAILED", data.get("outcome"));
        List<?> steps = (List<?>) producedBy(data).get("fallbackSteps");
        assertNotNull(steps, "降级足迹必须落 producedBy.fallbackSteps");
        assertTrue(steps.contains("repair:exhausted"), () -> "应记录修复用尽：" + steps);
        assertTrue(steps.contains("fail"), () -> "应记录 FAIL 步骤：" + steps);
    }

    /** 兜底值（§7.3）：仅兜底字段出错 → 替换为 Schema 声明值，结局 VALID_WITH_UNKNOWN + unknownPaths。 */
    @Test
    void fallbackValueYieldsValidWithUnknown() {
        registry.publish("lease.fb", 1, fallbackSchema(), "test");
        Map<String, OutputContract> byVersion = Map.of("v-fb", new OutputContract(
                "lease.fb@1", OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null, false,
                List.of(FallbackStep.FALLBACK_VALUE)));
        OutputStage fbStage = new OutputStage(MAPPER,
                v -> v == null ? null : byVersion.get(v),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink);

        LoopResult result = fbStage.onLoopExit(ctx("v-fb"), 0,
                new LoopPorts.ModelPort.Result("{\"findingId\":\"F-1\",\"conclusion\":\"MAYBE\"}", null,
                        List.of(), 10, 10, "mock"), 10, 10);

        assertNull(result.errorCode());
        assertEquals(0, modelPort.calls.get()); // 兜底不调用模型
        Map<String, Object> data = dataItems().get(0);
        assertEquals("VALID_WITH_UNKNOWN", data.get("outcome"));
        assertEquals(Boolean.TRUE, data.get("valid"));
        assertEquals(List.of("/conclusion"), data.get("unknownPaths"));
        assertEquals("UNKNOWN", MAPPER.valueToTree(data.get("payload")).path("conclusion").asText());
        assertTrue(((List<?>) producedBy(data).get("fallbackSteps")).contains("fallback_value:applied"));
    }

    /** 人工补录（§7.4）：阶梯首步 HUMAN → 生成任务、结局 HUMAN_PENDING、Turn 挂起。 */
    @Test
    void humanStepSuspendsWithPendingOutcome() {
        Map<String, OutputContract> byVersion = Map.of("v-human", new OutputContract(
                SCHEMA_REF, OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null, false,
                List.of(FallbackStep.HUMAN)));
        StubHumanTaskPort port = new StubHumanTaskPort();
        port.createTaskId = "htk-1";
        OutputStage humanStage = new OutputStage(MAPPER,
                v -> v == null ? null : byVersion.get(v),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink, port);

        LoopResult result = humanStage.onLoopExit(ctx("v-human"), 0,
                new LoopPorts.ModelPort.Result("{\"findingId\":\"F-1\"}", null,
                        List.of(), 10, 10, "mock"), 10, 10);

        assertEquals(TurnStatus.WAITING_APPROVAL, result.status());
        assertEquals("human_pending", result.errorCode());
        assertEquals("htk-1", result.errorMessage());
        assertEquals(0, modelPort.calls.get()); // 不发起修复调用
        Map<String, Object> data = dataItems().get(0);
        assertEquals("HUMAN_PENDING", data.get("outcome"));
        assertEquals(Boolean.FALSE, data.get("valid"));
        assertNull(data.get("payload")); // 待补录不交付部分对象
        assertEquals("htk-1", data.get("humanTaskId"));
        assertTrue(((List<?>) producedBy(data).get("fallbackSteps")).contains("human"));
    }

    /** 第 1 步完成状态：finish_reason=content_filter → CONTENT_FILTERED 可达，不修复并写 guardrail.checked。 */
    @Test
    void contentFilteredOutcomeReachable() {
        modelPort.script.add(new ScriptedModelPort.Step(
                payload("F-1", "SATISFIED", null, null), null, 50)); // 不应被消费
        modelPort.startIndex = 1;
        LoopResult result = stage.onLoopExit(ctx(), 0,
                new LoopPorts.ModelPort.Result("", null, List.of(), 10, 50, "mock", "content_filter"),
                10, 50);

        assertEquals("output_invalid", result.errorCode());
        assertEquals(0, modelPort.calls.get()); // 内容过滤不进入修复
        Map<String, Object> data = dataItems().get(0);
        assertEquals("FAILED", data.get("outcome"));
        assertEquals("CONTENT_FILTERED", data.get("reasonCode"));
        assertNull(data.get("payload")); // 不交付部分对象
        assertTrue(itemsOfType(ItemType.STATUS.wire()).stream()
                        .map(RuntimeItemDO::getContentJson)
                        .anyMatch(c -> c.contains("guardrail.checked") && c.contains("CONTENT_FILTERED")),
                "内容过滤应写 guardrail.checked（FLAG）供审计");
    }

    /** 续跑（§7.4）：命中已提交补录任务 → 写 VALID data item（producedBy.human）并收口，不再调用模型。 */
    @Test
    void resumePendingAppliesHumanAnswer() {
        StubHumanTaskPort port = new StubHumanTaskPort();
        port.submitted = new LoopPorts.HumanTaskPort.SubmittedCompletion(
                "htk-9", SCHEMA_REF, payload("F-1", "SATISFIED", null, null), "u-7");
        OutputStage resumeStage = new OutputStage(MAPPER,
                versionId -> versionId == null ? null : contractsByVersion.get(versionId),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink, port);

        LoopResult result = resumeStage.resumePending(ctx(), 3);

        assertNotNull(result);
        assertNull(result.errorCode());
        assertEquals(0, modelPort.calls.get()); // 续跑不再调用模型
        Map<String, Object> data = dataItems().get(0);
        assertEquals("VALID", data.get("outcome"));
        assertEquals(Boolean.TRUE, data.get("valid"));
        assertEquals("u-7", producedBy(data).get("human"));
    }

    /** 无已提交补录任务 → 不拦截，走常规循环。 */
    @Test
    void resumePendingNoopWithoutSubmission() {
        StubHumanTaskPort port = new StubHumanTaskPort();
        OutputStage resumeStage = new OutputStage(MAPPER,
                versionId -> versionId == null ? null : contractsByVersion.get(versionId),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink, port);
        assertNull(resumeStage.resumePending(ctx(), 0));
    }

    /** SCH-09：人工补录端口桩（create 返回固定任务 ID；takeSubmitted 返回预设提交）。 */
    private static final class StubHumanTaskPort implements LoopPorts.HumanTaskPort {
        String createTaskId;
        LoopPorts.HumanTaskPort.SubmittedCompletion submitted;

        @Override
        public String create(LoopContext ctx, int step, String schemaRef, String candidateJson,
                             List<String> pendingPointers) {
            return createTaskId;
        }

        @Override
        public LoopPorts.HumanTaskPort.SubmittedCompletion takeSubmitted(LoopContext ctx) {
            return submitted;
        }
    }

    private static JsonNode fallbackSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["findingId", "conclusion"],
                      "properties": {
                        "findingId": {"type": "string"},
                        "conclusion": {
                          "enum": ["SATISFIED", "NOT_SATISFIED", "UNKNOWN"],
                          "x-tioka-fallback": "UNKNOWN"
                        }
                      }
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- ADR-066 §10.3：变异测试（四结局之一 + 无异常逃逸 + VALID 载荷第二校验器复核） ----

    /**
     * 变异输入的「零逃逸」验收：每个输入恰好得到一个 data item，结局必属四结局之一；不抛出异常；
     * 交付（valid=true）的载荷须经**独立第二校验器**（本类手写结构复核）判定合法，并与权威校验器
     * （{@code SchemaRegistry.validate}，networknt）结论一致——二者都是冻结本体，互为佐证。
     *
     * <p>{@code HUMAN_PENDING} 未在本批可达（人工补录归 SCH-09），故只断言「四种结局之一」的成员性。
     */
    @Test
    void mutationOutcomesNeverEscape() {
        // maxRepair=0：不发起修复调用（桩脚本为空也不应被触达），变异输入一次评估即落 item
        Map<String, OutputContract> byVersion = Map.of("v-mut",
                new OutputContract(SCHEMA_REF, OutputMode.FINAL, 0, false, OutputStrategy.DIRECT, null, false));
        OutputStage mutStage = new OutputStage(MAPPER,
                v -> v == null ? null : byVersion.get(v),
                registry, modelPort, new ContextBuilder(MAPPER), items::list, sink);
        LoopContext ctx = ctx("v-mut");

        List<LoopPorts.ModelPort.Result> candidates = new ArrayList<>();
        candidates.add(result(payload("F-1", "SATISFIED", null, null)));                    // VALID
        candidates.add(result("{\"findingId\":\"F-1\"}"));                                  // 缺必填
        candidates.add(result("{\"findingId\":1,\"conclusion\":\"SATISFIED\"}"));           // 类型错
        candidates.add(result("{\"findingId\":\"F\",\"conclusion\":\"MAYBE\"}"));           // 枚举外
        candidates.add(result("{\"findingId\":\"F\",\"conclusion\":\"SATISFIED\",\"x\":1}")); // 额外字段
        candidates.add(result("不确定"));                                                   // NO_JSON
        candidates.add(result("{} {}"));                                                    // MULTIPLE_JSON
        candidates.add(result("{'a':1}"));                                                  // SYNTAX
        candidates.add(result(""));                                                         // EMPTY
        candidates.add(result(null));                                                       // EMPTY
        candidates.add(new LoopPorts.ModelPort.Result("{\"findingId\":\"F\",\"conclusion\":\"sat",
                null, List.of(), 10, 50, "mock", "length", false));                        // TRUNCATED
        candidates.add(new LoopPorts.ModelPort.Result("", null, List.of(), 10, 50, "mock", "stop", true)); // REFUSED
        candidates.add(new LoopPorts.ModelPort.Result("", null, List.of(), 10, 50, "mock",
                "content_filter", false));                                                  // CONTENT_FILTERED

        Set<String> observed = new java.util.LinkedHashSet<>();
        for (LoopPorts.ModelPort.Result cand : candidates) {
            items.list.clear();
            LoopResult r = assertDoesNotThrow(() -> mutStage.onLoopExit(ctx, 0, cand, 10, 50),
                    () -> "异常逃逸：" + cand.text());
            assertNotNull(r, () -> "必须返回 LoopResult：" + cand.text());
            List<Map<String, Object>> data = dataItems();
            assertEquals(1, data.size(), () -> "必须恰好写一个 data item：" + cand.text());
            String outcome = (String) data.get(0).get("outcome");
            observed.add(outcome);
            assertTrue(Set.of("VALID", "VALID_WITH_UNKNOWN", "HUMAN_PENDING", "FAILED").contains(outcome),
                    () -> "结局必属四结局之一：" + outcome);
            if (Boolean.TRUE.equals(data.get(0).get("valid"))) {
                JsonNode payloadNode = MAPPER.valueToTree(data.get(0).get("payload"));
                List<String> violations = structuralViolations(findingSchema(), payloadNode);
                assertTrue(violations.isEmpty(),
                        () -> "第二校验器判定不合法：" + violations + " ← " + payloadNode);
                assertTrue(registry.validate(SCHEMA_REF, payloadNode).isEmpty(),
                        () -> "权威校验器与第二校验器结论不一致 ← " + payloadNode);
            } else {
                assertNull(data.get(0).get("payload"), () -> "失败不得交付部分对象：" + cand.text());
                assertNotNull(data.get(0).get("reasonCode"), () -> "失败必须带原因码：" + cand.text());
            }
        }
        assertTrue(observed.contains("VALID"), "变异集应至少覆盖一次 VALID");
        assertTrue(observed.contains("FAILED"), "变异集应至少覆盖一次 FAILED");
    }

    private static LoopPorts.ModelPort.Result result(String text) {
        return new LoopPorts.ModelPort.Result(text, null, List.of(), 10, 50, "mock", "stop", false);
    }

    /**
     * 独立第二校验器（手写结构复核，ADR-066 §10.3）：覆盖 findingSchema 用到的关键字
     * （type / required / properties / additionalProperties=false / items / enum）。
     * 刻意不复用 networknt，作为对权威校验器的交叉佐证。
     */
    private static List<String> structuralViolations(JsonNode schema, JsonNode node) {
        List<String> out = new ArrayList<>();
        walk(schema, node, "", out);
        return out;
    }

    private static void walk(JsonNode schema, JsonNode node, String path, List<String> out) {
        if (schema == null || schema.isMissingNode()) {
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean hit = false;
            for (JsonNode e : enumNode) {
                if (e.equals(node)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                out.add(path + ": 不在枚举内");
            }
        }
        String type = schema.path("type").asText(null);
        if (type != null && !typeMatches(type, node)) {
            out.add(path + ": 类型应为 " + type);
            return;
        }
        if ("object".equals(type) && node.isObject()) {
            for (JsonNode req : schema.path("required")) {
                if (!node.has(req.asText())) {
                    out.add(path + "/" + req.asText() + ": 缺少必填字段");
                }
            }
            JsonNode props = schema.path("properties");
            boolean closed = schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode sub = props.get(e.getKey());
                if (sub == null) {
                    if (closed) {
                        out.add(path + "/" + e.getKey() + ": 不允许的额外字段");
                    }
                } else {
                    walk(sub, e.getValue(), path + "/" + e.getKey(), out);
                }
            }
        }
        if ("array".equals(type) && node.isArray()) {
            JsonNode items = schema.get("items");
            int i = 0;
            for (JsonNode el : node) {
                walk(items, el, path + "/" + i, out);
                i++;
            }
        }
    }

    private static boolean typeMatches(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;
        };
    }
}
