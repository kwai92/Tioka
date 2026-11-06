package com.tioka.runtime.kernel.application;

import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * M0 LeaseReaper（P1b）：租约过期回收。RUNNING 且 lease_until 过期 → INTERRUPTED → FAILED(LEASE_LOST)。
 * <p>W6 崩溃回收第二步将替换本类第二步；W5 保持 FAILED 语义，并经 {@link TurnDispatchQueue} 唤醒同 thread 下一条。
 */
@Component
public class LeaseReaper {
    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final TurnRepository turns;
    private final TurnDispatchQueue dispatchQueue;
    private final EventStream streams;
    private final RuntimeEventFactory events;

    public LeaseReaper(TurnRepository turns,
                       TurnDispatchQueue dispatchQueue,
                       EventStream streams,
                       RuntimeEventFactory events) {
        this.turns = turns;
        this.dispatchQueue = dispatchQueue;
        this.streams = streams;
        this.events = events;
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
            if (turns.failInterrupted(turnId, "LEASE_LOST", "lease expired") == 0) {
                continue;
            }
            log.warn("reaped expired turn {} on thread {}", turnId, row.getSessionId());
            streams.publish(row.getSessionId(), null, RuntimeEventType.TURN_FAILED.wire(),
                    events.turnStatus(RuntimeEventType.TURN_FAILED, row.getSessionId(), turnId,
                            TurnStatus.FAILED.name()));
            turns.nextQueued(row.getSessionId())
                    .ifPresent(t -> dispatchQueue.offer(t.getId(), row.getSessionId()));
        }
    }
}
