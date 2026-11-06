package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * RT-05：interrupt / steer 控制面（供 KernelRuntimeService 与测试直接调用）。
 */
@Service
public class TurnSignalService {
    private static final int STEER_MAX = 4000;

    private final TurnRepository turns;
    private final ItemRepository items;
    private final SignalBus signals;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ObjectMapper mapper;

    public TurnSignalService(TurnRepository turns,
                             ItemRepository items,
                             SignalBus signals,
                             EventStream streams,
                             RuntimeEventFactory events,
                             ObjectMapper mapper) {
        this.turns = turns;
        this.items = items;
        this.signals = signals;
        this.streams = streams;
        this.events = events;
        this.mapper = mapper;
    }

    public void interrupt(String turnId) {
        RuntimeTurnDO turn = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        TurnStatus st = TurnStatus.valueOf(turn.getStatus());
        if (st.isTerminal()) {
            return;
        }
        if (st == TurnStatus.QUEUED) {
            int n = turns.cancelQueued(turnId);
            if (n > 0) {
                String payload = events.turnStatus(
                        com.tioka.runtime.api.kernel.RuntimeEventType.TURN_CANCELLED,
                        turn.getSessionId(), turnId, TurnStatus.CANCELLED.name());
                streams.publish(turn.getSessionId(), null,
                        com.tioka.runtime.api.kernel.RuntimeEventType.TURN_CANCELLED.wire(), payload);
            }
            return;
        }
        if (st == TurnStatus.RUNNING
                || st == TurnStatus.WAITING_APPROVAL
                || st == TurnStatus.WAITING_CHILD
                || st == TurnStatus.INTERRUPTED) {
            signals.publishCancel(turnId);
            return;
        }
        throw new BizException(ErrorCode.CONFLICT, "当前状态不可取消: " + st);
    }

    public void steer(String turnId, String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "插话文本为空");
        }
        if (text.length() > STEER_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "插话超过 " + STEER_MAX + " 字符");
        }
        RuntimeTurnDO turn = turns.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "turn 不存在"));
        TurnStatus st = TurnStatus.valueOf(turn.getStatus());
        if (st == TurnStatus.QUEUED) {
            appendSteerItem(turn, text);
            return;
        }
        if (st == TurnStatus.RUNNING) {
            signals.publishSteer(turnId, text);
            return;
        }
        throw new BizException(ErrorCode.CONFLICT, "当前状态不可插话: " + st);
    }

    private void appendSteerItem(RuntimeTurnDO turn, String text) {
        try {
            String content = mapper.writeValueAsString(Map.of("text", text, "steer", true));
            RuntimeItemDO item = items.appendWithoutLease(
                    turn.getSessionId(), turn.getId(), 0, ItemType.MESSAGE.wire(), "user", content);
            streams.publish(turn.getSessionId(), EventId.of(item.getSeq()).toString(),
                    com.tioka.runtime.api.kernel.RuntimeEventType.ITEM_COMPLETED.wire(),
                    events.itemCompleted(turn.getSessionId(), item));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "插话写入失败");
        }
    }
}
