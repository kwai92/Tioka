package com.tioka.runtime.kernel.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RuntimeEventFactory {
    private final ObjectMapper mapper;

    public RuntimeEventFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String itemCompleted(String threadId, RuntimeItemDO item) {
        ObjectNode root = base(RuntimeEventType.ITEM_COMPLETED, threadId, item.getTurnId());
        ObjectNode it = root.putObject("item");
        it.put("itemId", item.getId());
        it.put("seq", item.getSeq());
        it.put("step", item.getStep() == null ? 0 : item.getStep());
        it.put("itemType", item.getType());
        if (item.getRole() != null) {
            it.put("role", item.getRole());
        }
        try {
            it.set("content", mapper.readTree(item.getContentJson() == null ? "{}" : item.getContentJson()));
        } catch (Exception e) {
            it.putObject("content");
        }
        return json(root);
    }

    public String itemCreated(String threadId, String turnId, String itemId, long seq, String itemType) {
        ObjectNode root = base(RuntimeEventType.ITEM_CREATED, threadId, turnId);
        ObjectNode it = root.putObject("item");
        it.put("itemId", itemId);
        it.put("seq", seq);
        it.put("itemType", itemType);
        it.put("role", "assistant");
        it.putObject("content");
        return json(root);
    }

    public String itemDelta(String threadId, String turnId, String itemId, int index, String text, String reasoning) {
        ObjectNode root = base(RuntimeEventType.ITEM_DELTA, threadId, turnId);
        ObjectNode d = root.putObject("delta");
        d.put("itemId", itemId);
        d.put("index", index);
        if (text != null) {
            d.put("text", text);
        }
        if (reasoning != null) {
            d.put("reasoning", reasoning);
        }
        return json(root);
    }

    public String itemDiscarded(String threadId, String turnId, String itemId, long seq, String itemType) {
        ObjectNode root = base(RuntimeEventType.ITEM_DISCARDED, threadId, turnId);
        ObjectNode it = root.putObject("item");
        it.put("itemId", itemId);
        it.put("seq", seq);
        it.put("itemType", itemType == null ? "message" : itemType);
        it.putObject("content");
        return json(root);
    }

    public String turnStatus(RuntimeEventType type, String threadId, String turnId, String status) {
        return turnStatus(type, threadId, turnId, status, null);
    }

    public String turnStatus(RuntimeEventType type, String threadId, String turnId, String status,
                             String waitReason) {
        ObjectNode root = base(type, threadId, turnId);
        ObjectNode turn = root.putObject("turn");
        turn.put("status", status);
        if (waitReason != null && !waitReason.isBlank()) {
            // FE-20：turn.waiting 带 reason=child
            turn.put("reason", "child");
            turn.put("waitReason", waitReason);
        }
        return json(root);
    }

    public String approvalRequested(String threadId, String turnId, String approvalId,
                                    String subjectType, String subjectId, String kind,
                                    String status, String toolName, String risk) {
        ObjectNode root = base(RuntimeEventType.APPROVAL_REQUESTED, threadId, turnId);
        ObjectNode a = root.putObject("approval");
        a.put("approvalId", approvalId);
        a.put("subjectType", subjectType);
        a.put("subjectId", subjectId);
        a.put("kind", kind);
        a.put("status", status);
        if (toolName != null) {
            a.put("toolName", toolName);
        }
        if (risk != null) {
            a.put("risk", risk);
        }
        return json(root);
    }

    public String approvalDecided(String threadId, String turnId, String approvalId,
                                  String subjectType, String subjectId, String kind,
                                  String status, String decidedBy) {
        ObjectNode root = base(RuntimeEventType.APPROVAL_DECIDED, threadId, turnId);
        ObjectNode a = root.putObject("approval");
        a.put("approvalId", approvalId);
        a.put("subjectType", subjectType);
        a.put("subjectId", subjectId);
        a.put("kind", kind);
        a.put("status", status);
        if (decidedBy != null) {
            a.put("decidedBy", decidedBy);
        }
        return json(root);
    }

    private ObjectNode base(RuntimeEventType type, String threadId, String turnId) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("type", type.wire());
        root.put("threadId", threadId);
        if (turnId != null) {
            root.put("turnId", turnId);
        }
        root.put("ts", Instant.now().toString());
        return root;
    }

    private String json(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"schemaVersion\":1,\"type\":\"item.completed\",\"threadId\":\"\",\"ts\":\""
                    + Instant.now() + "\"}";
        }
    }
}
