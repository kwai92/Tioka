package com.tioka.runtime.kernel.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
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
import java.util.concurrent.ThreadPoolExecutor;

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

    /** startTurn 在入队前探测：池与队列都满则返回 false。 */
    public boolean canAccept() {
        if (executor instanceof ThreadPoolExecutor tpe) {
            return tpe.getQueue().remainingCapacity() > 0
                    || tpe.getActiveCount() < tpe.getMaximumPoolSize();
        }
        return true;
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
                    // 锁被占时 TurnRunner 直接返回（仍 QUEUED）：不自旋重投，
                    // 等当前持锁 Turn 结束时 onTurnFinished → nextQueued 保证 FIFO。
                    if (!stillQueued && sessionId != null) {
                        onTurnFinished(sessionId);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            throw new BizException(ErrorCode.DEPENDENCY, "本实例执行队列已满");
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
