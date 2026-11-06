package com.tioka.runtime.kernel.application;

import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    /** 本实例正在执行的 Turn（RT-05 SignalBus 本地路由）。 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

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
                try {
                    concurrency.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                running.add(turnId);
                String sessionId = null;
                boolean stillQueued = false;
                try {
                    runner.run(turnId);
                } finally {
                    running.remove(turnId);
                    concurrency.release();
                    Optional<RuntimeTurnDO> row = turns.findById(turnId);
                    if (row.isPresent()) {
                        sessionId = row.get().getSessionId();
                        stillQueued = "QUEUED".equals(row.get().getStatus());
                    }
                    if (stillQueued) {
                        // 抢锁/acquire 失败提前返回：短暂退避后重新入队，避免永久 QUEUED
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        submit(turnId);
                    } else if (sessionId != null) {
                        onTurnFinished(sessionId);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 队列满：保持 QUEUED，由后续 onTurnFinished 再捞
        }
    }

    public boolean isRunningLocally(String turnId) {
        return running.contains(turnId);
    }

    public void onTurnFinished(String threadId) {
        Optional<RuntimeTurnDO> next = turns.nextQueued(threadId);
        next.ifPresent(t -> submit(t.getId()));
    }
}
