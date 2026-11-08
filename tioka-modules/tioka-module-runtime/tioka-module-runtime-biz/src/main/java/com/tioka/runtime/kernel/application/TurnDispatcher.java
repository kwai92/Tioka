package com.tioka.runtime.kernel.application;

import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RT-06 W5：每实例一个读线程，消费组 {@code rt-workers}，提交本机有界线程池执行。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.runtime", name = "dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class TurnDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TurnDispatcher.class);
    private static final Duration BLOCK = Duration.ofMillis(2000);

    private final TurnDispatchQueue queue;
    private final TurnRunner runner;
    private final ExecutorService executor;
    private final KernelProperties props;
    private final Semaphore concurrency;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    /** 测试可关闭本实例消费（{@link #stopConsuming()}），不影响其它实例。 */
    private final AtomicBoolean consuming = new AtomicBoolean(true);
    private Thread reader;

    public TurnDispatcher(TurnDispatchQueue queue,
                          TurnRunner runner,
                          ExecutorService turnExecutor,
                          KernelProperties props) {
        this.queue = queue;
        this.runner = runner;
        this.executor = turnExecutor;
        this.props = props;
        this.concurrency = new Semaphore(props.getExecutor().getMaxConcurrentTurns());
    }

    @PostConstruct
    void start() {
        reader = new Thread(this::loop, "rt-turn-dispatcher");
        reader.setDaemon(true);
        reader.start();
    }

    @PreDestroy
    void shutdown() {
        alive.set(false);
        if (reader != null) {
            reader.interrupt();
        }
    }

    /** IT：关闭本实例 Dispatcher，消息由其它实例消费。 */
    public void stopConsuming() {
        consuming.set(false);
    }

    public void startConsuming() {
        consuming.set(true);
    }

    public boolean isConsuming() {
        return consuming.get();
    }

    public boolean isRunningLocally(String turnId) {
        return running.contains(turnId);
    }

    private void loop() {
        String consumer = props.getInstanceId();
        while (alive.get()) {
            try {
                if (!consuming.get()) {
                    Thread.sleep(50);
                    continue;
                }
                int free = concurrency.availablePermits();
                if (free == 0) {
                    concurrency.acquire();
                    concurrency.release();
                    continue;
                }
                int count = Math.min(free, 16);
                List<MapRecord<String, Object, Object>> msgs = queue.readGroup(consumer, count, BLOCK);
                for (MapRecord<String, Object, Object> m : msgs) {
                    String turnId = TurnDispatchQueue.turnIdOf(m);
                    String threadId = TurnDispatchQueue.threadIdOf(m);
                    queue.ack(m.getId());
                    if (!consuming.get()) {
                        // 读阻塞期间被 stopConsuming：重新投递，供其它实例或稍后恢复消费
                        if (turnId != null && threadId != null) {
                            queue.offer(turnId, threadId);
                        }
                        continue;
                    }
                    if (turnId == null || turnId.isBlank()) {
                        continue;
                    }
                    submit(turnId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!alive.get()) {
                    return;
                }
                log.warn("TurnDispatcher loop error: {}", e.toString());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void submit(String turnId) {
        try {
            executor.execute(() -> {
                try {
                    concurrency.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                running.add(turnId);
                try {
                    runner.run(turnId);
                } finally {
                    running.remove(turnId);
                    concurrency.release();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("turn executor full, leave QUEUED for sweeper turnId={}", turnId);
        }
    }
}
