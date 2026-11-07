package com.tioka.runtime.kernel.application;

import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RT-06 W5：兜底扫描陈旧 QUEUED，在 Redis 丢失后从数据库重建 {@code rt:turns}。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.runtime", name = "queued-sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class QueuedSweeper {
    private static final Logger log = LoggerFactory.getLogger(QueuedSweeper.class);

    private final TurnRepository turns;
    private final TurnDispatchQueue queue;

    public QueuedSweeper(TurnRepository turns, TurnDispatchQueue queue) {
        this.turns = turns;
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.queued-sweeper-interval-ms:15000}")
    @Transactional
    public void sweep() {
        if (!turns.tryAdvisoryLockQueuedSweeper()) {
            return;
        }
        try {
            List<RuntimeTurnDO> stale = turns.findStaleQueuedForDispatch();
            for (RuntimeTurnDO t : stale) {
                if (t.getId() == null || t.getThreadId() == null) {
                    continue;
                }
                if (!queue.tryMarkDispatched(t.getId())) {
                    continue;
                }
                queue.offer(t.getId(), t.getThreadId());
                log.debug("QueuedSweeper re-dispatched turn {} thread {}", t.getId(), t.getThreadId());
            }
        } finally {
            turns.unlockAdvisoryLockQueuedSweeper();
        }
    }
}
