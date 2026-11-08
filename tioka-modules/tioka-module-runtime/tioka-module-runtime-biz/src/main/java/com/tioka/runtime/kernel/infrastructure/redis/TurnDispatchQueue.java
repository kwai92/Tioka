package com.tioka.runtime.kernel.infrastructure.redis;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RT-06：跨实例 Turn 派发队列（Redis Stream {@code rt:turns} + 消费组 {@code rt-workers}）。
 */
@Component
public class TurnDispatchQueue {
    private static final Logger log = LoggerFactory.getLogger(TurnDispatchQueue.class);

    public static final String STREAM_KEY = "rt:turns";
    public static final String GROUP = "rt-workers";
    private static final long MAXLEN = 100_000L;
    private static final Duration DISPATCHED_TTL = Duration.ofSeconds(15);

    private final StringRedisTemplate redis;

    public TurnDispatchQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void ensureGroupOnStartup() {
        ensureConsumerGroup();
    }

    /** 启动时与 FLUSHALL 后重建消费组；已存在则忽略 BUSYGROUP。 */
    public void ensureConsumerGroup() {
        try {
            Boolean created = redis.execute((RedisCallback<Boolean>) connection -> {
                try {
                    connection.streamCommands().xGroupCreate(
                            STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                            GROUP,
                            ReadOffset.latest(),
                            true);
                    return Boolean.TRUE;
                } catch (Exception e) {
                    if (isBusyGroup(e)) {
                        return Boolean.FALSE;
                    }
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException(e);
                }
            });
            if (Boolean.TRUE.equals(created)) {
                log.info("created redis stream group {} on {}", GROUP, STREAM_KEY);
            }
        } catch (Exception e) {
            if (!isBusyGroup(e)) {
                throw new IllegalStateException("无法创建消费组 " + GROUP + " on " + STREAM_KEY, e);
            }
        }
    }

    /** afterCommit / nextQueued / sweeper：投递「去看看」提示。{@code XADD} 带 {@code MAXLEN ~ 100000}。 */
    public void offer(String turnId, String threadId) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(threadId, "threadId");
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "turnId", turnId,
                "threadId", threadId
        )).withStreamKey(STREAM_KEY));
        redis.opsForStream().trim(STREAM_KEY, MAXLEN, true);
    }

    /**
     * QueuedSweeper 防风暴：{@code SET rt:dispatched:{turnId} 1 NX EX 15} 成功才允许再次 XADD。
     */
    public boolean tryMarkDispatched(String turnId) {
        Boolean ok = redis.opsForValue().setIfAbsent(dispatchedKey(turnId), "1", DISPATCHED_TTL);
        return Boolean.TRUE.equals(ok);
    }

    public List<MapRecord<String, Object, Object>> readGroup(String consumerName, int count, Duration block) {
        if (count <= 0) {
            return List.of();
        }
        StreamOperations<String, Object, Object> streams = redis.opsForStream();
        List<MapRecord<String, Object, Object>> rows = streams.read(
                org.springframework.data.redis.connection.stream.Consumer.from(GROUP, consumerName),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                        .count(count)
                        .block(block),
                org.springframework.data.redis.connection.stream.StreamOffset.create(
                        STREAM_KEY, ReadOffset.lastConsumed()));
        return rows == null ? List.of() : rows;
    }

    public void ack(RecordId id) {
        if (id == null) {
            return;
        }
        redis.opsForStream().acknowledge(STREAM_KEY, GROUP, id);
    }

    public static String turnIdOf(MapRecord<String, Object, Object> record) {
        if (record == null || record.getValue() == null) {
            return null;
        }
        Object v = record.getValue().get("turnId");
        return v == null ? null : String.valueOf(v);
    }

    public static String threadIdOf(MapRecord<String, Object, Object> record) {
        if (record == null || record.getValue() == null) {
            return null;
        }
        Object v = record.getValue().get("threadId");
        return v == null ? null : String.valueOf(v);
    }

    public static String dispatchedKey(String turnId) {
        return "rt:dispatched:" + turnId;
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
