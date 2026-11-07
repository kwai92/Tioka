package com.tioka.runtime.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-04 HCI-06 映射表全覆盖。
 */
class AgUiEventMapperTest {

    private static final String TID = "agui-thr-1";
    private static final String RUN = "run-1";

    private final ObjectMapper om = new ObjectMapper();
    private AgUiEventMapper mapper;
    private AgUiMappingState state;

    @BeforeEach
    void setUp() {
        mapper = new AgUiEventMapper(om);
        state = new AgUiMappingState();
    }

    @Test
    void turnStarted_mapsToRunStarted() {
        ObjectNode ev = base("turn.started");
        ev.putObject("turn").put("status", "RUNNING");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals(AgUiEventType.RUN_STARTED, out.get(0).path("type").asText());
        assertEquals(TID, out.get(0).path("threadId").asText());
        assertEquals(RUN, out.get(0).path("runId").asText());
    }

    @Test
    void itemCreated_messageAssistant_mapsToTextStart() {
        ObjectNode ev = base("item.created");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "itm_1");
        item.put("itemType", "message");
        item.put("role", "assistant");
        item.putObject("content");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals(AgUiEventType.TEXT_MESSAGE_START, out.get(0).path("type").asText());
        assertEquals("itm_1", out.get(0).path("messageId").asText());
        assertEquals("assistant", out.get(0).path("role").asText());
        assertTrue(state.wasTextStarted("itm_1"));
    }

    @Test
    void itemDelta_message_mapsToTextContent() {
        state.markTextStarted("itm_1");
        ObjectNode ev = base("item.delta");
        ObjectNode d = ev.putObject("delta");
        d.put("itemId", "itm_1");
        d.put("index", 1);
        d.put("text", "hel");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals(AgUiEventType.TEXT_MESSAGE_CONTENT, out.get(0).path("type").asText());
        assertEquals("hel", out.get(0).path("delta").asText());
    }

    @Test
    void itemCompleted_messageWithStream_mapsToTextEnd() {
        state.markTextStarted("itm_1");
        ObjectNode ev = base("item.completed");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "itm_1");
        item.put("itemType", "message");
        item.put("role", "assistant");
        item.putObject("content").put("text", "hello");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals(AgUiEventType.TEXT_MESSAGE_END, out.get(0).path("type").asText());
    }

    @Test
    void itemCompleted_messageWithoutStream_emitsStartContentEnd() {
        ObjectNode ev = base("item.completed");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "itm_2");
        item.put("itemType", "message");
        item.put("role", "assistant");
        item.putObject("content").put("text", "full");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(3, out.size());
        assertEquals(AgUiEventType.TEXT_MESSAGE_START, out.get(0).path("type").asText());
        assertEquals(AgUiEventType.TEXT_MESSAGE_CONTENT, out.get(1).path("type").asText());
        assertEquals("full", out.get(1).path("delta").asText());
        assertEquals(AgUiEventType.TEXT_MESSAGE_END, out.get(2).path("type").asText());
    }

    @Test
    void reasoning_createdDeltaCompleted_mapsToThinkingEvents() {
        ObjectNode created = base("item.created");
        ObjectNode cItem = created.putObject("item");
        cItem.put("itemId", "rea_1");
        cItem.put("itemType", "reasoning");
        cItem.putObject("content");
        assertEquals(AgUiEventType.THINKING_TEXT_MESSAGE_START,
                mapper.map(created, state, TID, RUN).get(0).path("type").asText());

        ObjectNode delta = base("item.delta");
        ObjectNode d = delta.putObject("delta");
        d.put("itemId", "rea_1");
        d.put("reasoning", "think");
        assertEquals(AgUiEventType.THINKING_TEXT_MESSAGE_CONTENT,
                mapper.map(delta, state, TID, RUN).get(0).path("type").asText());

        ObjectNode completed = base("item.completed");
        ObjectNode item = completed.putObject("item");
        item.put("itemId", "rea_1");
        item.put("itemType", "reasoning");
        item.putObject("content").put("reasoning", "think");
        assertEquals(AgUiEventType.THINKING_TEXT_MESSAGE_END,
                mapper.map(completed, state, TID, RUN).get(0).path("type").asText());
    }

    @Test
    void reasoning_completedWithoutStream_emitsTriple() {
        ObjectNode completed = base("item.completed");
        ObjectNode item = completed.putObject("item");
        item.put("itemId", "rea_2");
        item.put("itemType", "reasoning");
        item.putObject("content").put("reasoning", "done");
        List<ObjectNode> out = mapper.map(completed, state, TID, RUN);
        assertEquals(3, out.size());
        assertEquals(AgUiEventType.THINKING_TEXT_MESSAGE_START, out.get(0).path("type").asText());
        assertEquals(AgUiEventType.THINKING_TEXT_MESSAGE_CONTENT, out.get(1).path("type").asText());
        assertEquals(AgUiEventType.THINKING_TEXT_MESSAGE_END, out.get(2).path("type").asText());
    }

    @Test
    void toolCall_completed_emitsStartArgsEnd() throws Exception {
        ObjectNode ev = base("item.completed");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "tc_1");
        item.put("itemType", "tool_call");
        ObjectNode content = item.putObject("content");
        content.put("callId", "call_9");
        content.put("name", "echo");
        content.putObject("arguments").put("x", 1);
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(3, out.size());
        assertEquals(AgUiEventType.TOOL_CALL_START, out.get(0).path("type").asText());
        assertEquals("call_9", out.get(0).path("toolCallId").asText());
        assertEquals("echo", out.get(0).path("toolCallName").asText());
        assertEquals(AgUiEventType.TOOL_CALL_ARGS, out.get(1).path("type").asText());
        assertEquals("{\"x\":1}", out.get(1).path("delta").asText());
        assertEquals(AgUiEventType.TOOL_CALL_END, out.get(2).path("type").asText());
    }

    @Test
    void toolResult_completed_mapsToToolCallResult() {
        ObjectNode ev = base("item.completed");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "tr_1");
        item.put("itemType", "tool_result");
        ObjectNode content = item.putObject("content");
        content.put("callId", "call_9");
        content.put("output", "ok");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals(AgUiEventType.TOOL_CALL_RESULT, out.get(0).path("type").asText());
        assertEquals("call_9", out.get(0).path("toolCallId").asText());
        assertEquals("tr_1", out.get(0).path("messageId").asText());
        assertEquals("ok", out.get(0).path("content").asText());
    }

    @Test
    void itemDiscarded_mapsToCustom() {
        ObjectNode ev = base("item.discarded");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "itm_x");
        item.put("itemType", "message");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals(AgUiEventType.CUSTOM, out.get(0).path("type").asText());
        assertEquals("tioka.item_discarded", out.get(0).path("name").asText());
        assertEquals("itm_x", out.get(0).path("value").path("messageId").asText());
    }

    @Test
    void approvalRequested_mapsToCustom() {
        ObjectNode ev = base("approval.requested");
        ObjectNode a = ev.putObject("approval");
        a.put("approvalId", "ap_1");
        a.put("kind", "tool");
        a.put("toolName", "erp.update");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals("tioka.approval_requested", out.get(0).path("name").asText());
        assertEquals("ap_1", out.get(0).path("value").path("approvalId").asText());
    }

    @Test
    void turnWaiting_mapsToCustomAndKeepsOpenSemantics() {
        ObjectNode ev = base("turn.waiting");
        ev.putObject("turn").put("status", "WAITING_APPROVAL");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(1, out.size());
        assertEquals("tioka.turn_waiting", out.get(0).path("name").asText());
        assertEquals("WAITING_APPROVAL", out.get(0).path("value").path("reason").asText());
    }

    @Test
    void turnCompleted_mapsToRunFinished() {
        ObjectNode ev = base("turn.completed");
        ev.putObject("turn").put("status", "COMPLETED");
        List<ObjectNode> out = mapper.map(ev, state, TID, RUN);
        assertEquals(AgUiEventType.RUN_FINISHED, out.get(0).path("type").asText());
        assertEquals(TID, out.get(0).path("threadId").asText());
        assertEquals(RUN, out.get(0).path("runId").asText());
    }

    @Test
    void turnFailedAndCancelled_mapToRunError() {
        ObjectNode failed = base("turn.failed");
        failed.putObject("turn").put("status", "FAILED");
        ObjectNode f = mapper.map(failed, state, TID, RUN).get(0);
        assertEquals(AgUiEventType.RUN_ERROR, f.path("type").asText());
        assertEquals("FAILED", f.path("code").asText());

        ObjectNode cancelled = base("turn.cancelled");
        cancelled.putObject("turn").put("status", "CANCELLED");
        ObjectNode c = mapper.map(cancelled, new AgUiMappingState(), TID, RUN).get(0);
        assertEquals(AgUiEventType.RUN_ERROR, c.path("type").asText());
        assertEquals("CANCELLED", c.path("code").asText());
    }

    @Test
    void otherItemTypes_mapToCustomTiokaItem() {
        for (String type : List.of("citation", "card", "status", "file_ref")) {
            AgUiMappingState s = new AgUiMappingState();
            ObjectNode ev = base("item.completed");
            ObjectNode item = ev.putObject("item");
            item.put("itemId", "itm_" + type);
            item.put("seq", 3);
            item.put("itemType", type);
            item.putObject("content").put("k", "v");
            List<ObjectNode> out = mapper.map(ev, s, TID, RUN);
            assertEquals(1, out.size(), type);
            assertEquals(AgUiEventType.CUSTOM, out.get(0).path("type").asText(), type);
            assertEquals("tioka.item", out.get(0).path("name").asText(), type);
            assertEquals(type, out.get(0).path("value").path("type").asText(), type);
        }
    }

    @Test
    void clientToolsUnsupported_helper() {
        ObjectNode n = mapper.clientToolsUnsupported();
        assertEquals(AgUiEventType.RUN_ERROR, n.path("type").asText());
        assertEquals("CLIENT_TOOLS_UNSUPPORTED", n.path("code").asText());
    }

    @Test
    void userMessageCompleted_ignored() {
        ObjectNode ev = base("item.completed");
        ObjectNode item = ev.putObject("item");
        item.put("itemId", "u1");
        item.put("itemType", "message");
        item.put("role", "user");
        item.putObject("content").put("text", "hi");
        assertTrue(mapper.map(ev, state, TID, RUN).isEmpty());
    }

    private ObjectNode base(String type) {
        ObjectNode n = om.createObjectNode();
        n.put("schemaVersion", 1);
        n.put("type", type);
        n.put("threadId", "thr_internal");
        n.put("turnId", "turn_1");
        n.put("ts", "2026-11-07T00:00:00Z");
        return n;
    }
}
