package com.tioka.runtime.kernel.application;

import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * 本实例有界线程池 + 同 thread 串行（靠 SessionLock）。
 */
@Component
public class TurnScheduler {
    private final TurnRepository turns;
    private final TurnRunner runner;
    private final ExecutorService executor;
    private final Semaphore concurrency;

    public TurnScheduler(TurnRepository turns,
                         @Lazy TurnRunner runner,
                         ExecutorService turnExecutor,
                         KernelProperties props) {
        this.turns = turns;
        this.runner = runner;
        this.executor = turnExecutor;
        this.concurrency = new Semaphore(props.getExecutor().getMaxConcurrentTurns());
    }

    public void submit(String turnId) {
        try {
            executor.execute(() -> {
                if (!concurrency.tryAcquire()) {
                    return;
                }
                try {
                    runner.run(turnId);
                } finally {
                    concurrency.release();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 队列满：保持 QUEUED，由后续 onTurnFinished 再捞
        }
    }

    public void onTurnFinished(String threadId) {
        Optional<RuntimeTurnDO> next = turns.nextQueued(threadId);
        next.ifPresent(t -> submit(t.getId()));
    }
}
