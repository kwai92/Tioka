package com.tioka.model.application.gateway;

import com.tioka.framework.id.Ids;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MH-05：用量异步批量写入 {@code model_usage}。
 * 入队后由后台线程每 1 秒或满 200 条批量 INSERT；进程退出前 flush。
 */
@Component
public class UsageRecorder implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);
    private static final int BATCH = 200;
    private static final long FLUSH_INTERVAL_MS = 1_000L;

    private final ModelUsageRepository usages;
    private final LinkedBlockingQueue<ModelUsageDO> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock flushLock = new ReentrantLock();
    private Thread worker;

    public UsageRecorder(ModelUsageRepository usages) {
        this.usages = usages;
    }

    /**
     * @param status SUCCESS / ERROR / FAILOVER
     */
    public void record(ModelCallContext ctx,
                       String bindingId,
                       String modelRef,
                       int promptTokens,
                       int completionTokens,
                       long inputPriceMicros,
                       long outputPriceMicros,
                       int latencyMs,
                       String status) {
        ModelUsageDO row = new ModelUsageDO();
        row.setId(Ids.newId("usage"));
        if (ctx != null && ctx.principal() != null) {
            row.setWorkspaceId(ctx.principal().workspaceId());
            row.setUserId(ctx.principal().userId());
            row.setCreateBy(ctx.principal().userId());
        }
        if (ctx != null) {
            row.setAgentId(ctx.agentId());
            row.setSessionId(ctx.sessionId());
            row.setTurnId(ctx.turnId());
            row.setPurpose(ctx.purpose() == null ? null : ctx.purpose().name());
        }
        row.setBindingId(bindingId);
        row.setModelRef(modelRef);
        row.setPromptTokens(Math.max(0, promptTokens));
        row.setCompletionTokens(Math.max(0, completionTokens));
        row.setCostMicros(costMicros(promptTokens, completionTokens, inputPriceMicros, outputPriceMicros));
        row.setLatencyMs(Math.max(0, latencyMs));
        row.setStatus(status == null ? "ERROR" : status);
        row.setCreateAt(LocalDateTime.now());
        if (!queue.offer(row)) {
            log.warn("model_usage 队列拒绝写入 id={}", row.getId());
            return;
        }
        if (queue.size() >= BATCH) {
            flush();
        }
    }

    /** {@code cost_micros = prompt × input_price / 1e6 + completion × output_price / 1e6}（四舍五入）。 */
    public static long costMicros(int promptTokens, int completionTokens,
                                  long inputPriceMicros, long outputPriceMicros) {
        double cost = Math.max(0, promptTokens) * (double) Math.max(0L, inputPriceMicros) / 1_000_000d
                + Math.max(0, completionTokens) * (double) Math.max(0L, outputPriceMicros) / 1_000_000d;
        return Math.round(cost);
    }

    /** 测试与停机共用：排空队列并批量落库。 */
    public void flush() {
        flushLock.lock();
        try {
            List<ModelUsageDO> batch = new ArrayList<>(BATCH);
            while (true) {
                batch.clear();
                queue.drainTo(batch, BATCH);
                if (batch.isEmpty()) {
                    return;
                }
                try {
                    usages.insertBatch(batch);
                } catch (RuntimeException e) {
                    log.error("model_usage 批量写入失败 size={}", batch.size(), e);
                    throw e;
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::loop, "usage-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            flush();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        while (running.get()) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                flush();
            } catch (RuntimeException e) {
                log.warn("usage-recorder 周期 flush 失败: {}", e.getMessage());
            }
        }
    }
}
