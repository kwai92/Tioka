package com.tioka.runtime.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime SSE 事件 → AG-UI 事件（纯函数 + 显式 {@link AgUiMappingState}；M2-04 HCI-06 映射表）。
 *
 * <p>{@code outThreadId}/{@code runId} 为 AG-UI 侧标识（请求体 threadId/runId），不是内部 turnId。
 * ObjectMapper 由 Spring 注入（ENG-05）。
 */
@Component
public final class AgUiEventMapper {
    private final ObjectMapper mapper;

    public AgUiEventMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param runtimeEvent Runtime 事件 JSON（含 type / item / delta / turn / approval）
     * @param state        跨事件状态（同一 run 内复用）
     * @param outThreadId  写出到 AG-UI 的 threadId
     * @param runId        写出到 AG-UI 的 runId
     * @return 0..N 个 AG-UI 事件（Jackson ObjectNode，camelCase）
     */
    public List<ObjectNode> map(JsonNode runtimeEvent, AgUiMappingState state,
                                String outThreadId, String runId) {
        if (runtimeEvent == null || runtimeEvent.isNull()) {
            return List.of();
        }
        String type = text(runtimeEvent, "type");
        if (type == null || type.isBlank()) {
            return List.of();
        }
        RuntimeEventType rt;
        try {
            rt = wireToType(type);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return switch (rt) {
            case TURN_STARTED -> List.of(runStarted(outThreadId, runId));
            case TURN_COMPLETED -> List.of(runFinished(outThreadId, runId));
            case TURN_FAILED, TURN_CANCELLED -> List.of(runError(runtimeEvent, rt));
            case TURN_WAITING -> List.of(custom("tioka.turn_waiting",
                    Map.of("reason", turnReason(runtimeEvent))));
            case ITEM_CREATED -> mapItemCreated(runtimeEvent, state);
            case ITEM_DELTA -> mapItemDelta(runtimeEvent, state);
            case ITEM_COMPLETED -> mapItemCompleted(runtimeEvent, state);
            case ITEM_DISCARDED -> List.of(custom("tioka.item_discarded",
                    Map.of("messageId", itemId(runtimeEvent))));
            case APPROVAL_REQUESTED -> List.of(custom("tioka.approval_requested",
                    approvalValue(runtimeEvent)));
            case APPROVAL_DECIDED -> List.of();
        };
    }

    private List<ObjectNode> mapItemCreated(JsonNode event, AgUiMappingState state) {
        JsonNode item = event.path("item");
        String itemType = text(item, "itemType");
        String role = text(item, "role");
        String messageId = text(item, "itemId");
        if (messageId == null) {
            return List.of();
        }
        if (ItemType.MESSAGE.wire().equals(itemType) && "assistant".equals(role)) {
            state.markTextStarted(messageId);
            return List.of(textStart(messageId));
        }
        if (ItemType.REASONING.wire().equals(itemType)) {
            state.markThinkingStarted(messageId);
            return List.of(thinkingStart(messageId));
        }
        return List.of();
    }

    private List<ObjectNode> mapItemDelta(JsonNode event, AgUiMappingState state) {
        JsonNode delta = event.path("delta");
        String messageId = text(delta, "itemId");
        if (messageId == null) {
            return List.of();
        }
        List<ObjectNode> out = new ArrayList<>(2);
        if (delta.hasNonNull("text")) {
            if (!state.wasTextStarted(messageId)) {
                state.markTextStarted(messageId);
                out.add(textStart(messageId));
            }
            out.add(textContent(messageId, delta.get("text").asText()));
        }
        if (delta.hasNonNull("reasoning")) {
            if (!state.wasThinkingStarted(messageId)) {
                state.markThinkingStarted(messageId);
                out.add(thinkingStart(messageId));
            }
            out.add(thinkingContent(messageId, delta.get("reasoning").asText()));
        }
        return out;
    }

    private List<ObjectNode> mapItemCompleted(JsonNode event, AgUiMappingState state) {
        JsonNode item = event.path("item");
        String itemType = text(item, "itemType");
        String role = text(item, "role");
        String messageId = text(item, "itemId");
        if (messageId == null || itemType == null) {
            return List.of();
        }
        JsonNode content = item.path("content");
        if (ItemType.MESSAGE.wire().equals(itemType)) {
            if (!"assistant".equals(role)) {
                return List.of();
            }
            if (state.wasTextStarted(messageId)) {
                return List.of(textEnd(messageId));
            }
            String body = contentText(content);
            state.markTextStarted(messageId);
            return List.of(textStart(messageId), textContent(messageId, body), textEnd(messageId));
        }
        if (ItemType.REASONING.wire().equals(itemType)) {
            if (state.wasThinkingStarted(messageId)) {
                return List.of(thinkingEnd(messageId));
            }
            String body = content.hasNonNull("reasoning") ? content.get("reasoning").asText("") : contentText(content);
            state.markThinkingStarted(messageId);
            return List.of(thinkingStart(messageId), thinkingContent(messageId, body), thinkingEnd(messageId));
        }
        if (ItemType.TOOL_CALL.wire().equals(itemType)) {
            return mapToolCall(messageId, content);
        }
        if (ItemType.TOOL_RESULT.wire().equals(itemType)) {
            return List.of(toolResult(messageId, content));
        }
        // citation / card / status / file_ref / 其他
        return List.of(custom("tioka.item", itemViewValue(event, item)));
    }

    private List<ObjectNode> mapToolCall(String itemId, JsonNode content) {
        String callId = text(content, "callId");
        String name = text(content, "name");
        if (callId == null) {
            callId = itemId;
        }
        if (name == null) {
            name = "";
        }
        String argsDelta;
        try {
            JsonNode args = content.get("arguments");
            argsDelta = args == null || args.isNull() ? "{}" : mapper.writeValueAsString(args);
        } catch (Exception e) {
            argsDelta = "{}";
        }
        return List.of(
                toolCallStart(callId, name),
                toolCallArgs(callId, argsDelta),
                toolCallEnd(callId));
    }

    private ObjectNode toolResult(String itemId, JsonNode content) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TOOL_CALL_RESULT);
        n.put("toolCallId", text(content, "callId") == null ? itemId : text(content, "callId"));
        n.put("messageId", itemId);
        JsonNode output = content.get("output");
        if (output == null || output.isNull()) {
            n.put("content", "");
        } else if (output.isTextual()) {
            n.put("content", output.asText());
        } else {
            n.put("content", output.toString());
        }
        return n;
    }

    private ObjectNode itemViewValue(JsonNode event, JsonNode item) {
        ObjectNode v = mapper.createObjectNode();
        v.put("itemId", text(item, "itemId"));
        v.put("threadId", text(event, "threadId"));
        v.put("turnId", text(event, "turnId"));
        if (item.has("seq")) {
            v.put("seq", item.get("seq").asLong());
        }
        if (item.has("step")) {
            v.put("step", item.get("step").asInt());
        }
        v.put("type", text(item, "itemType"));
        if (item.hasNonNull("role")) {
            v.put("role", text(item, "role"));
        }
        if (item.has("content")) {
            v.set("content", item.get("content"));
        }
        return v;
    }

    private Object approvalValue(JsonNode event) {
        JsonNode a = event.path("approval");
        if (a.isMissingNode() || a.isNull()) {
            return Map.of();
        }
        return a;
    }

    private static String turnReason(JsonNode event) {
        JsonNode turn = event.path("turn");
        String status = text(turn, "status");
        return status == null ? "WAITING" : status;
    }

    private static String contentText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        if (content.hasNonNull("text")) {
            return content.get("text").asText("");
        }
        return "";
    }

    private static String itemId(JsonNode event) {
        String id = text(event.path("item"), "itemId");
        return id == null ? "" : id;
    }

    private ObjectNode runStarted(String threadId, String runId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.RUN_STARTED);
        n.put("threadId", threadId);
        n.put("runId", runId);
        return n;
    }

    private ObjectNode runFinished(String threadId, String runId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.RUN_FINISHED);
        n.put("threadId", threadId);
        n.put("runId", runId);
        return n;
    }

    private ObjectNode runError(JsonNode event, RuntimeEventType rt) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.RUN_ERROR);
        String status = text(event.path("turn"), "status");
        String code = status == null ? rt.wire() : status;
        n.put("code", code);
        String message = text(event, "message");
        if (message == null) {
            message = text(event.path("turn"), "errorMessage");
        }
        if (message == null) {
            message = code;
        }
        n.put("message", message);
        return n;
    }

    private ObjectNode custom(String name, Object value) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.CUSTOM);
        n.put("name", name);
        n.set("value", mapper.valueToTree(value));
        return n;
    }

    private ObjectNode textStart(String messageId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TEXT_MESSAGE_START);
        n.put("messageId", messageId);
        n.put("role", "assistant");
        return n;
    }

    private ObjectNode textContent(String messageId, String delta) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TEXT_MESSAGE_CONTENT);
        n.put("messageId", messageId);
        n.put("delta", delta == null ? "" : delta);
        return n;
    }

    private ObjectNode textEnd(String messageId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TEXT_MESSAGE_END);
        n.put("messageId", messageId);
        return n;
    }

    private ObjectNode thinkingStart(String messageId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.THINKING_TEXT_MESSAGE_START);
        n.put("messageId", messageId);
        return n;
    }

    private ObjectNode thinkingContent(String messageId, String delta) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.THINKING_TEXT_MESSAGE_CONTENT);
        n.put("messageId", messageId);
        n.put("delta", delta == null ? "" : delta);
        return n;
    }

    private ObjectNode thinkingEnd(String messageId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.THINKING_TEXT_MESSAGE_END);
        n.put("messageId", messageId);
        return n;
    }

    private ObjectNode toolCallStart(String toolCallId, String toolCallName) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TOOL_CALL_START);
        n.put("toolCallId", toolCallId);
        n.put("toolCallName", toolCallName);
        return n;
    }

    private ObjectNode toolCallArgs(String toolCallId, String delta) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TOOL_CALL_ARGS);
        n.put("toolCallId", toolCallId);
        n.put("delta", delta);
        return n;
    }

    private ObjectNode toolCallEnd(String toolCallId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.TOOL_CALL_END);
        n.put("toolCallId", toolCallId);
        return n;
    }

    private static RuntimeEventType wireToType(String wire) {
        for (RuntimeEventType t : RuntimeEventType.values()) {
            if (t.wire().equals(wire)) {
                return t;
            }
        }
        throw new IllegalArgumentException(wire);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    /** 客户端 tools 非空时的即时错误事件。 */
    public ObjectNode clientToolsUnsupported() {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", AgUiEventType.RUN_ERROR);
        n.put("code", "CLIENT_TOOLS_UNSUPPORTED");
        n.put("message", "M2 不支持客户端 tools");
        return n;
    }
}
