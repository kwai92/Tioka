package com.tioka.infra.messaging;

import com.tioka.infra.messaging.infrastructure.SysOutboxRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Outbox 中继：Redis 锁单例、按 id 顺序投递、同 msg_key 失败时本轮跳过后续行、定期清理。
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SysOutboxRepository outbox;
    private final MessageTransport transport;
    private final RelayLock relayLock;
    private final MessagingProperties props;
    private final OutboxNotifyWaiter notifyWaiter;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String instanceToken = "relay-" + UUID.randomUUID();
    private volatile Thread loopThread;
    private volatile Instant lastCleanup = Instant.EPOCH;

    public OutboxRelay(SysOutboxRepository outbox,
                       MessageTransport transport,
                       RelayLock relayLock,
                       MessagingProperties props,
                       OutboxNotifyWaiter notifyWaiter) {
        this.outbox = outbox;
        this.transport = transport;
        this.relayLock = relayLock;
        this.props = props;
        this.notifyWaiter = notifyWaiter;
    }

    @PostConstruct
    void start() {
        if (!props.isRelayEnabled()) {
            log.info("OutboxRelay 已禁用（tioka.messaging.relay-enabled=false）");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        loopThread = new Thread(this::loop, "tioka-outbox-relay");
        loopThread.setDaemon(true);
        loopThread.start();
        log.info("OutboxRelay 已启动 token={}", instanceToken);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        notifyWaiter.wakeup();
        if (loopThread != null) {
            loopThread.interrupt();
        }
        relayLock.release(instanceToken);
    }

    private void loop() {
        long lastRenewAt = 0L;
        boolean holding = false;
        while (running.get()) {
            try {
                if (!holding) {
                    holding = relayLock.tryAcquire(instanceToken);
                    if (!holding) {
                        sleepQuiet(props.getRelay().getLockRenewInterval().toMillis());
                        continue;
                    }
                    lastRenewAt = System.currentTimeMillis();
                    log.info("OutboxRelay 获得锁 token={}", instanceToken);
                } else if (System.currentTimeMillis() - lastRenewAt
                        >= props.getRelay().getLockRenewInterval().toMillis()) {
                    if (!relayLock.renew(instanceToken)) {
                        holding = false;
                        log.warn("OutboxRelay 续期失败，释放领导权");
                        continue;
                    }
                    lastRenewAt = System.currentTimeMillis();
                }

                int sent = drainBatch();
                maybeCleanup();
                if (sent == 0) {
                    notifyWaiter.await(props.getRelay().getIdleWait().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("OutboxRelay 循环异常: {}", e.toString());
                sleepQuiet(props.getRelay().getIdleWait().toMillis());
            }
        }
        if (holding) {
            relayLock.release(instanceToken);
        }
    }

    /**
     * 测试钩子：仅当 {@code token} 能续期（持有锁）时投递一批；{@code onSent} 每成功一条回调一次。
     */
    public int drainOnceForTest(String token, IntConsumer onSent) {
        if (!relayLock.renew(token)) {
            return 0;
        }
        return drainBatch(onSent);
    }

    private int drainBatch() {
        return drainBatch(null);
    }

    private int drainBatch(IntConsumer onSent) {
        List<SysOutboxDO> rows = outbox.findUnsent(props.getRelay().getBatch());
        if (rows.isEmpty()) {
            return 0;
        }
        Set<String> blockedKeys = new HashSet<>();
        int sent = 0;
        for (SysOutboxDO row : rows) {
            String key = row.getMsgKey() == null ? "" : row.getMsgKey();
            if (!key.isEmpty() && blockedKeys.contains(key)) {
                continue;
            }
            try {
                transport.send(row);
                if (outbox.markSent(row.getId(), Instant.now())) {
                    sent++;
                    if (onSent != null) {
                        onSent.accept(1);
                    }
                }
            } catch (Exception e) {
                log.warn("投递失败 id={} eventId={}: {}", row.getId(), row.getEventId(), e.toString());
                outbox.markAttemptFailed(row.getId(), e.toString());
                if (!key.isEmpty()) {
                    blockedKeys.add(key);
                }
            }
        }
        return sent;
    }

    private void maybeCleanup() {
        Instant now = Instant.now();
        if (now.isBefore(lastCleanup.plusSeconds(3600))) {
            return;
        }
        Instant before = now.minus(props.getRelay().getRetention());
        int deleted = outbox.deleteSentBefore(before);
        lastCleanup = now;
        if (deleted > 0) {
            log.info("OutboxRelay 清理已发送行 deleted={} before={}", deleted, before);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
