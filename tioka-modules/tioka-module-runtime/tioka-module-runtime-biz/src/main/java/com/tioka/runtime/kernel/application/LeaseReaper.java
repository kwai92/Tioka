package com.tioka.runtime.kernel.application;

import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * M0 LeaseReaper（P1b）：租约过期回收。RUNNING 且 lease_until 过期 → INTERRUPTED → FAILED(LEASE_LOST)。
 */
@Component
public class LeaseReaper {
    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final TurnRepository turns;
    private final TurnScheduler scheduler;
    private final EventStream streams;
    private final RuntimeEventFactory events;

    public LeaseReaper(TurnRepository turns,
                       TurnScheduler scheduler,
                       EventStream streams,
                       RuntimeEventFactory events) {
        this.turns = turns;
        this.scheduler = scheduler;
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
            scheduler.onTurnFinished(row.getSessionId());
        }
    }
}
