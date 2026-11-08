package com.tioka.runtime.kernel.child;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子任务完成 / 超时：写 resolves tool_result，WAITING_CHILD → QUEUED（RT-09）。
 */
@Service
public class ChildResumeService {
    private static final Logger log = LoggerFactory.getLogger(ChildResumeService.class);

    private final TurnRepository turns;
    private final ItemRepository items;
    private final TurnDispatchQueue dispatchQueue;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ObjectMapper mapper;
    private final PrincipalHolder principals;

    public ChildResumeService(TurnRepository turns,
                              ItemRepository items,
                              TurnDispatchQueue dispatchQueue,
                              EventStream streams,
                              RuntimeEventFactory events,
                              ObjectMapper mapper,
                              PrincipalHolder principals) {
        this.turns = turns;
        this.items = items;
        this.dispatchQueue = dispatchQueue;
        this.streams = streams;
        this.events = events;
        this.mapper = mapper;
        this.principals = principals;
    }

    @Transactional
    public boolean resume(String turnId, String callId, boolean ok, Object output, String errorCode) {
        if (principals.find().isEmpty()) {
            return principals.runAs(SystemPrincipals.system(),
                    () -> resumeAs(turnId, callId, ok, output, errorCode));
        }
        return resumeAs(turnId, callId, ok, output, errorCode);
    }

    private boolean resumeAs(String turnId, String callId, boolean ok, Object output, String errorCode) {
        if (turnId == null || turnId.isBlank() || callId == null || callId.isBlank()) {
            return false;
        }
        RuntimeTurnDO turn = turns.findById(turnId).orElse(null);
        if (turn == null || !TurnStatus.WAITING_CHILD.name().equals(turn.getStatus())) {
            return false;
        }
        RuntimeItemDO placeholder = findPendingPlaceholder(turnId, callId);
        if (placeholder == null) {
            log.debug("child resume: no pending placeholder turnId={} callId={}", turnId, callId);
            // 可能已续跑；若仍 WAITING_CHILD 且无 pending，仍尝试 requeue
            return requeueIfIdle(turn);
        }
        if (alreadyResolved(turnId, callId, placeholder.getSeq())) {
            return requeueIfIdle(turn);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", callId);
        content.put("name", readName(placeholder));
        content.put("ok", ok);
        content.put("output", output == null ? "" : output);
        content.put("resolves", placeholder.getSeq());
        if (errorCode != null && !errorCode.isBlank()) {
            content.put("errorCode", errorCode);
        }
        int step = placeholder.getStep() == null ? 0 : placeholder.getStep();
        RuntimeItemDO item = items.appendWithoutLease(ItemWrite.withoutLease(
                turn.getThreadId(), turnId, step,
                ItemType.TOOL_RESULT.wire(), "tool", json(content)));
        streams.publish(turn.getThreadId(), EventId.of(item.getSeq()).toString(),
                RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(turn.getThreadId(), item));

        int n = turns.requeueFromWaitingChild(turnId);
        if (n == 0) {
            return false;
        }
        dispatchQueue.offer(turnId, turn.getThreadId());
        return true;
    }

    private boolean requeueIfIdle(RuntimeTurnDO turn) {
        if (!hasUnresolvedPending(turn.getId())) {
            int n = turns.requeueFromWaitingChild(turn.getId());
            if (n > 0) {
                dispatchQueue.offer(turn.getId(), turn.getThreadId());
                return true;
            }
        }
        return false;
    }

    private boolean hasUnresolvedPending(String turnId) {
        List<RuntimeItemDO> list = items.listByTurn(turnId);
        for (RuntimeItemDO i : list) {
            if (!ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                continue;
            }
            JsonNode root = readTree(i.getContentJson());
            if (root != null && root.has("pending") && !root.has("resolves")) {
                String callId = root.path("callId").asText(null);
                if (callId != null && !alreadyResolved(turnId, callId, i.getSeq())) {
                    return true;
                }
            }
        }
        return false;
    }

    private RuntimeItemDO findPendingPlaceholder(String turnId, String callId) {
        for (RuntimeItemDO i : items.listByTurn(turnId)) {
            if (!ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                continue;
            }
            JsonNode root = readTree(i.getContentJson());
            if (root == null || !root.has("pending")) {
                continue;
            }
            if (callId.equals(root.path("callId").asText(null))) {
                return i;
            }
        }
        return null;
    }

    private boolean alreadyResolved(String turnId, String callId, Long placeholderSeq) {
        for (RuntimeItemDO i : items.listByTurn(turnId)) {
            if (!ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                continue;
            }
            JsonNode root = readTree(i.getContentJson());
            if (root == null || !root.has("resolves")) {
                continue;
            }
            if (!callId.equals(root.path("callId").asText(null))) {
                continue;
            }
            if (placeholderSeq != null && root.path("resolves").asLong(-1) == placeholderSeq) {
                return true;
            }
            if (placeholderSeq == null) {
                return true;
            }
        }
        return false;
    }

    private String readName(RuntimeItemDO placeholder) {
        JsonNode root = readTree(placeholder.getContentJson());
        return root == null ? "" : root.path("name").asText("");
    }

    private JsonNode readTree(String json) {
        try {
            return json == null || json.isBlank() ? null : mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
