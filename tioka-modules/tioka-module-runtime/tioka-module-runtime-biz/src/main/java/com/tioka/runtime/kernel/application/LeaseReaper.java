package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租约过期回收（M0 第一步 + RT-06 W6 崩溃续跑第二步）。
 * <p>RUNNING 且 lease_until 过期 → INTERRUPTED；若 {@code recover_count >= max-recover} 则
 * FAILED(LEASE_LOST)，否则补丢失 tool_result / {@code item.discarded} 后 → QUEUED 并 XADD。
 */
@Component
public class LeaseReaper {
    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);
    public static final String LOST_TOOL_OUTPUT = "执行实例中断，工具结果丢失，未重放";

    private final TurnRepository turns;
    private final ItemRepository items;
    private final TurnDispatchQueue dispatchQueue;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final KernelProperties props;
    private final ObjectMapper mapper;
    private final PrincipalHolder principals;

    public LeaseReaper(TurnRepository turns,
                       ItemRepository items,
                       TurnDispatchQueue dispatchQueue,
                       EventStream streams,
                       RuntimeEventFactory events,
                       KernelProperties props,
                       ObjectMapper mapper,
                       PrincipalHolder principals) {
        this.turns = turns;
        this.items = items;
        this.dispatchQueue = dispatchQueue;
        this.streams = streams;
        this.events = events;
        this.props = props;
        this.mapper = mapper;
        this.principals = principals;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.lease-reaper-interval-ms:10000}")
    public void reap() {
        List<String> expired = turns.findExpired(Instant.now(), 50);
        for (String turnId : expired) {
            RuntimeTurnDO row = turns.findById(turnId).orElse(null);
            if (row == null) {
                continue;
            }
            if (turns.markInterrupted(turnId) == 0) {
                continue;
            }
            RuntimeTurnDO interrupted = turns.findById(turnId).orElse(null);
            if (interrupted == null) {
                continue;
            }
            recoverOrFail(interrupted);
        }
    }

    private void recoverOrFail(RuntimeTurnDO row) {
        String turnId = row.getId();
        String threadId = row.getThreadId();
        int recoverCount = row.getRecoverCount() == null ? 0 : row.getRecoverCount();
        int maxRecover = Math.max(0, props.getMaxRecover());

        discardInFlightStream(threadId, turnId);

        if (recoverCount >= maxRecover) {
            if (turns.failInterrupted(turnId, "LEASE_LOST", "max recover exceeded") == 0) {
                return;
            }
            log.warn("gave up turn {} after recover_count={} (max={})", turnId, recoverCount, maxRecover);
            streams.publish(threadId, null, RuntimeEventType.TURN_FAILED.wire(),
                    events.turnStatus(RuntimeEventType.TURN_FAILED, threadId, turnId, TurnStatus.FAILED.name()));
            turns.nextQueued(threadId)
                    .ifPresent(t -> dispatchQueue.offer(t.getId(), threadId));
            return;
        }

        principals.runAs(SystemPrincipals.system(), () -> {
            writeLostToolResults(row);
            return null;
        });
        if (turns.requeueInterrupted(turnId) == 0) {
            return;
        }
        log.warn("requeued expired turn {} on thread {} (recover_count {}→{})",
                turnId, threadId, recoverCount, recoverCount + 1);
        dispatchQueue.offer(turnId, threadId);
    }

    private void discardInFlightStream(String threadId, String turnId) {
        String cur = streams.currentEntry(threadId);
        if (cur == null || cur.isBlank()) {
            return;
        }
        EventStream.StreamEvent ev = streams.getEntry(threadId, cur);
        streams.clearCurrentEntry(threadId);
        if (ev == null) {
            return;
        }
        long seq;
        String itemId;
        String itemType = ItemType.MESSAGE.wire();
        try {
            if (ev.eventId() != null && !ev.eventId().isBlank()) {
                seq = EventId.parse(ev.eventId()).seq();
            } else {
                return;
            }
            JsonNode data = mapper.readTree(ev.dataJson() == null ? "{}" : ev.dataJson());
            JsonNode item = data.path("item");
            itemId = item.path("itemId").asText(Ids.newId("itm"));
            if (item.hasNonNull("itemType")) {
                itemType = item.get("itemType").asText(itemType);
            }
        } catch (Exception e) {
            log.debug("skip discard parse for {}: {}", threadId, e.toString());
            return;
        }
        streams.publish(threadId, EventId.of(seq).toString(), RuntimeEventType.ITEM_DISCARDED.wire(),
                events.itemDiscarded(threadId, turnId, itemId, seq, itemType));
    }

    private void writeLostToolResults(RuntimeTurnDO turn) {
        List<RuntimeItemDO> turnItems = items.listByThread(turn.getThreadId()).stream()
                .filter(i -> turn.getId().equals(i.getTurnId()))
                .filter(i -> !Boolean.TRUE.equals(i.getDiscarded()))
                .toList();
        if (turnItems.isEmpty()) {
            return;
        }
        Set<String> done = new HashSet<>();
        for (RuntimeItemDO i : turnItems) {
            if (ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                String callId = readCallId(i.getContentJson());
                if (callId != null) {
                    done.add(callId);
                }
            }
        }
        for (RuntimeItemDO i : turnItems) {
            if (!ItemType.TOOL_CALL.wire().equals(i.getType())) {
                continue;
            }
            String callId = readCallId(i.getContentJson());
            String name = readName(i.getContentJson());
            if (callId == null || done.contains(callId)) {
                continue;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("callId", callId);
            content.put("name", name == null ? "" : name);
            content.put("ok", false);
            content.put("output", LOST_TOOL_OUTPUT);
            content.put("durationMs", 0);
            int step = i.getStep() == null ? 0 : i.getStep();
            RuntimeItemDO result = items.appendWithoutLease(ItemWrite.withoutLease(
                    turn.getThreadId(), turn.getId(), step,
                    ItemType.TOOL_RESULT.wire(), "tool", json(content)));
            streams.publish(turn.getThreadId(), EventId.of(result.getSeq()).toString(),
                    RuntimeEventType.ITEM_COMPLETED.wire(),
                    events.itemCompleted(turn.getThreadId(), result));
            done.add(callId);
        }
    }

    private String readCallId(String contentJson) {
        try {
            JsonNode n = mapper.readTree(contentJson == null ? "{}" : contentJson);
            String id = n.path("callId").asText(null);
            return id == null || id.isBlank() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private String readName(String contentJson) {
        try {
            return mapper.readTree(contentJson == null ? "{}" : contentJson).path("name").asText("");
        } catch (Exception e) {
            return "";
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
