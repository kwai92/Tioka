package com.tioka.runtime.kernel.events;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.mapper.RuntimeTurnMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Service
public class ThreadEventsService {
    private final RuntimeSessionRepository sessions;
    private final ItemRepository items;
    private final RuntimeTurnMapper turns;
    private final EventStream streams;
    private final StreamPump pump;
    private final RuntimeEventFactory events;
    private final PrincipalHolder principals;
    private final KernelProperties props;

    public ThreadEventsService(RuntimeSessionRepository sessions,
                               ItemRepository items,
                               RuntimeTurnMapper turns,
                               EventStream streams,
                               StreamPump pump,
                               RuntimeEventFactory events,
                               PrincipalHolder principals,
                               KernelProperties props) {
        this.sessions = sessions;
        this.items = items;
        this.turns = turns;
        this.streams = streams;
        this.pump = pump;
        this.events = events;
        this.principals = principals;
        this.props = props;
    }

    public SseEmitter open(String threadId, String lastEventId) {
        requireVisible(threadId);
        if (!pump.tryAcquireConnection()) {
            throw new BizException(ErrorCode.DEPENDENCY, "SSE 连接数已满");
        }
        String L = streams.latestEntryId(threadId);
        SseEmitter emitter = new SseEmitter(props.getEvents().getSseTimeout().toMillis());
        EventId sent = EventId.parse(lastEventId);
        try {
            // 1. 补发活动 Turn 状态
            RuntimeTurnDO active = turns.findActive(threadId);
            if (active != null) {
                RuntimeEventType type = switch (TurnStatus.valueOf(active.getStatus())) {
                    case RUNNING -> RuntimeEventType.TURN_STARTED;
                    case WAITING_APPROVAL, WAITING_CHILD -> RuntimeEventType.TURN_WAITING;
                    case COMPLETED -> RuntimeEventType.TURN_COMPLETED;
                    case FAILED -> RuntimeEventType.TURN_FAILED;
                    case CANCELLED -> RuntimeEventType.TURN_CANCELLED;
                    default -> RuntimeEventType.TURN_STARTED;
                };
                pump.send(emitter, null, type.wire(),
                        events.turnStatus(type, threadId, active.getId(), active.getStatus()));
            }

            // 2. DB 补齐 item.completed
            long afterSeq = sent.hasDelta() ? Math.max(0, sent.seq() - 1) : sent.seq();
            List<RuntimeItemDO> backlog = items.listAfterSeq(threadId, afterSeq, 500);
            for (RuntimeItemDO item : backlog) {
                EventId eid = EventId.of(item.getSeq());
                if (eid.compareTo(sent) <= 0) {
                    continue;
                }
                pump.send(emitter, eid.toString(), RuntimeEventType.ITEM_COMPLETED.wire(),
                        events.itemCompleted(threadId, item));
                sent = eid;
            }

            // 3. 当前流式中的 delta（rt:cur → L）
            String cur = streams.currentEntry(threadId);
            if (cur != null && !cur.isBlank()) {
                List<EventStream.StreamEvent> live = streams.range(threadId, cur, L);
                for (EventStream.StreamEvent ev : live) {
                    if (ev.eventId() == null || ev.eventId().isBlank()) {
                        continue;
                    }
                    EventId eid = EventId.parse(ev.eventId());
                    if (eid.compareTo(sent) <= 0) {
                        continue;
                    }
                    pump.send(emitter, ev.eventId(), ev.type(), ev.dataJson());
                    sent = eid;
                }
            }
        } catch (Exception e) {
            pump.releaseConnection();
            emitter.completeWithError(e);
            return emitter;
        }

        pump.subscribe(threadId, L, sent, emitter);
        return emitter;
    }

    private void requireVisible(String threadId) {
        RuntimeSessionDO row = sessions.find(threadId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        Principal p = principals.find().orElse(null);
        if (p == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (p.type() == PrincipalType.SYSTEM) {
            return;
        }
        if (!p.userId().equals(row.getOwnerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
    }
}
