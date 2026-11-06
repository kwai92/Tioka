package com.tioka.runtime.kernel.application;

import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R-2：afterCommit / onTurnFinished 在池满时留下的 QUEUED Turn，周期性补调度。
 */
@Component
public class QueuedTurnRescanner {
    private static final Logger log = LoggerFactory.getLogger(QueuedTurnRescanner.class);

    private final TurnRepository turns;
    private final TurnScheduler scheduler;

    public QueuedTurnRescanner(TurnRepository turns, TurnScheduler scheduler) {
        this.turns = turns;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.queued-rescan-interval-ms:5000}")
    public void rescan() {
        if (!scheduler.canAccept()) {
            return;
        }
        List<String> heads = turns.findOrphanQueuedHeads(32);
        for (String turnId : heads) {
            if (scheduler.isRunningLocally(turnId)) {
                continue;
            }
            if (scheduler.trySubmit(turnId)) {
                log.debug("rescanned orphan QUEUED turn {}", turnId);
            } else {
                break;
            }
        }
    }
}