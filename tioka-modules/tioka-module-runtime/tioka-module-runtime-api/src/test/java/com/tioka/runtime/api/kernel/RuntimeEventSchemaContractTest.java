package com.tioka.runtime.api.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SSE 事件 Schema 与 Java 枚举保持一致（ADR-016、ADR-030）。 */
class RuntimeEventSchemaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode schemaNode;
    private static JsonSchema schema;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = RuntimeEventSchemaContractTest.class.getResourceAsStream("/schema/runtime-event-v1.json")) {
            assertNotNull(in, "runtime-event-v1.json must exist on classpath");
            schemaNode = MAPPER.readTree(in);
        }
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    @Test
    void eventTypeEnumMatchesJava() {
        List<String> inSchema = new ArrayList<>();
        schemaNode.at("/properties/type/enum").forEach(n -> inSchema.add(n.asText()));
        List<String> inJava = Arrays.stream(RuntimeEventType.values()).map(RuntimeEventType::wire).toList();
        assertEquals(inJava, inSchema);
    }

    @Test
    void turnStatusEnumMatchesJava() {
        List<String> inSchema = new ArrayList<>();
        schemaNode.at("/$defs/turn/properties/status/enum").forEach(n -> inSchema.add(n.asText()));
        List<String> inJava = Arrays.stream(TurnStatus.values()).map(Enum::name).toList();
        assertEquals(inJava, inSchema);
    }

    @Test
    void itemTypeExamplesMatchJava() {
        List<String> inSchema = new ArrayList<>();
        schemaNode.at("/$defs/item/properties/itemType/examples").forEach(n -> inSchema.add(n.asText()));
        List<String> inJava = Arrays.stream(ItemType.values()).map(ItemType::wire).toList();
        assertEquals(inJava, inSchema);
    }

    @Test
    void acceptsDeltaAndRejectsMissingPayload() throws Exception {
        JsonNode ok = MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-05T08:00:00Z","delta":{"itemId":"i1","index":0,"text":"he"}}""");
        assertTrue(schema.validate(ok).isEmpty(), () -> schema.validate(ok).toString());

        JsonNode bad = MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-05T08:00:00Z"}""");
        assertFalse(schema.validate(bad).isEmpty());
    }

    @Test
    void acceptsGenericApproval() throws Exception {
        JsonNode ev = MAPPER.readTree("""
                {"schemaVersion":1,"type":"approval.requested","threadId":"t1",
                 "ts":"2026-11-05T08:00:00Z",
                 "approval":{"approvalId":"a1","subjectType":"flow_node_run","subjectId":"n1",
                             "kind":"card","status":"PENDING"}}""");
        assertTrue(schema.validate(ev).isEmpty(), () -> schema.validate(ev).toString());
    }

    /**
     * P2-L8：运行时实际产出样本回归。样本逐字段对齐 runtime-biz 的
     * {@code RuntimeEventFactory}（kernel/events/RuntimeEventFactory.java）真实产出——
     * 该工厂经 StreamPump 以 SSE data 原样下发，其 JSON 即线上报文。
     * 工厂字段变更而 schema 未同步时，此处即红。
     */
    @Test
    void runtimeEmittedSamplesAllValidate() throws Exception {
        List<String> samples = List.of(
                // turn.started —— KernelRuntimeService: turnStatus(TURN_STARTED, .., QUEUED)
                """
                {"schemaVersion":1,"type":"turn.started","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","turn":{"status":"QUEUED"}}""",
                // turn.waiting（审批挂起）—— ApprovalSuspendService
                """
                {"schemaVersion":1,"type":"turn.waiting","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","turn":{"status":"WAITING_APPROVAL"}}""",
                // turn.waiting（子线程挂起，带 reason=child + waitReason）—— ChildWaitService / FE-20
                """
                {"schemaVersion":1,"type":"turn.waiting","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "turn":{"status":"WAITING_CHILD","reason":"child","waitReason":"call-9"}}""",
                // turn.completed / turn.failed / turn.cancelled —— TurnRunner / LeaseReaper / TurnSignalService
                """
                {"schemaVersion":1,"type":"turn.completed","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","turn":{"status":"COMPLETED"}}""",
                """
                {"schemaVersion":1,"type":"turn.failed","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","turn":{"status":"FAILED"}}""",
                """
                {"schemaVersion":1,"type":"turn.cancelled","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","turn":{"status":"CANCELLED"}}""",
                // item.created —— MemoryItemSink: itemCreated(.., MESSAGE, role=assistant, content={})
                """
                {"schemaVersion":1,"type":"item.created","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "item":{"itemId":"i1","seq":1,"itemType":"message","role":"assistant","content":{}}}""",
                // item.delta —— MemoryItemSink: text 与 reasoning 均可为空
                """
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","delta":{"itemId":"i1","index":0,"text":"He"}}""",
                """
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "delta":{"itemId":"i2","index":3,"text":"ans","reasoning":"thinking"}}""",
                // item.completed（普通消息）—— RuntimeEventFactory#itemCompleted(RuntimeItemDO)
                """
                {"schemaVersion":1,"type":"item.completed","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "item":{"itemId":"i1","seq":1,"step":0,"itemType":"message","role":"assistant",
                          "content":{"type":"text","text":"Hello"}}}""",
                // item.completed（审批卡片）—— ApprovalSuspendService 追加的 CARD item
                """
                {"schemaVersion":1,"type":"item.completed","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "item":{"itemId":"i5","seq":5,"step":2,"itemType":"card","role":"system",
                          "content":{"kind":"tool","approvalId":"a1","toolName":"web_search",
                                     "args":{},"risk":"HIGH","reason":"外呼搜索"}}}""",
                // item.discarded —— 故障接力作废半成品 item（ADR-029 第 2 条）
                """
                {"schemaVersion":1,"type":"item.discarded","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "item":{"itemId":"i1","seq":1,"itemType":"message","content":{}}}""",
                // approval.requested —— ApprovalSuspendService: subjectType=turn, kind=tool
                """
                {"schemaVersion":1,"type":"approval.requested","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "approval":{"approvalId":"a1","subjectType":"turn","subjectId":"u1","kind":"tool",
                              "status":"PENDING","toolName":"web_search","risk":"HIGH"}}""",
                // approval.decided —— ApprovalDecideService: 带 decidedBy
                """
                {"schemaVersion":1,"type":"approval.decided","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "approval":{"approvalId":"a1","subjectType":"turn","subjectId":"u1","kind":"tool",
                              "status":"APPROVED","decidedBy":"user-1"}}""");

        assertEquals(RuntimeEventType.values().length, samples.stream()
                        .map(s -> uncheckedTree(s).get("type").asText()).distinct().count(),
                "样本应覆盖全部事件类型");
        for (String s : samples) {
            JsonNode ev = MAPPER.readTree(s);
            var messages = schema.validate(ev);
            assertTrue(messages.isEmpty(), () -> "运行时样本未通过 schema 校验：" + s + " -> " + messages);
        }
    }

    /** P2-L8：坏样本必须被 schema 拒绝——缺载荷、非法枚举、越界数值。 */
    @Test
    void corruptedSamplesAreRejected() throws Exception {
        // turn 事件缺 turn 载荷
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"turn.completed","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z"}""")).isEmpty());
        // item 事件缺 item 载荷
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.created","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z"}""")).isEmpty());
        // delta 缺必填 index
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","delta":{"itemId":"i1"}}""")).isEmpty());
        // approval.requested 缺必填载荷
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"approval.requested","threadId":"t1",
                 "ts":"2026-11-09T04:00:00Z"}""")).isEmpty());
        // turn.status 非法枚举
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"turn.started","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z","turn":{"status":"RUNNING_FAST"}}""")).isEmpty());
        // approval.kind 非法枚举
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"approval.requested","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "approval":{"approvalId":"a1","subjectType":"turn","subjectId":"u1","kind":"sms",
                              "status":"PENDING"}}""")).isEmpty());
        // item.seq 低于最小值 1（运行时 seq 从 1 起）
        assertFalse(schema.validate(MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.created","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-09T04:00:00Z",
                 "item":{"itemId":"i1","seq":0,"itemType":"message","role":"assistant","content":{}}}""")).isEmpty());
    }

    private static JsonNode uncheckedTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
