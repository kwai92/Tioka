package com.tioka.runtime.kernel.application.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.agent.api.spec.OutputMode;
import com.tioka.agent.api.spec.OutputStrategy;
import com.tioka.ontology.application.schema.EvidenceBinder;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.ontology.domain.TextNormalizer;
import com.tioka.ontology.infrastructure.DocumentRepository;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import com.tioka.runtime.api.kernel.ItemType;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

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
            "v-final", new OutputContract(SCHEMA_REF, OutputMode.FINAL, 2, false, OutputStrategy.DIRECT, null),
            "v-evidence", new OutputContract(SCHEMA_REF, OutputMode.FINAL, 2, true, OutputStrategy.DIRECT, null),
            "v-tool", new OutputContract(SCHEMA_REF, OutputMode.TOOL, 2, false, OutputStrategy.DIRECT, null));

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
}
