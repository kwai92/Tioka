package com.tioka.runtime.kernel.infrastructure.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RT-05：跨实例取消 / 插话信号。
 * <ul>
 *   <li>cancel：{@code SET rt:signal:{turnId}:cancel 1 EX 3600} + {@code PUBLISH rt:signal}</li>
 *   <li>steer：{@code RPUSH rt:signal:{turnId}:steer} + PUBLISH</li>
 * </ul>
 */
@Component
public class SignalBus implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(SignalBus.class);
    public static final String CHANNEL = "rt:signal";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RedisMessageListenerContainer container;
    private final ConcurrentHashMap<String, TurnFlags> local = new ConcurrentHashMap<>();

    public SignalBus(StringRedisTemplate redis,
                     ObjectMapper mapper,
                     RedisMessageListenerContainer container) {
        this.redis = redis;
        this.mapper = mapper;
        this.container = container;
    }

    @PostConstruct
    void subscribe() {
        container.addMessageListener(this, new ChannelTopic(CHANNEL));
        if (!container.isRunning()) {
            container.start();
        }
    }

    @PreDestroy
    void unsubscribe() {
        container.removeMessageListener(this);
    }

    /** 本实例开始执行 Turn 时注册本地标志。 */
    public TurnFlags register(String turnId) {
        TurnFlags flags = new TurnFlags();
        local.put(turnId, flags);
        String existing = redis.opsForValue().get(cancelKey(turnId));
        if (existing != null) {
            flags.cancelRequested.set(true);
            if (!existing.isBlank() && !"1".equals(existing)) {
                flags.cancelReason = existing;
            }
        }
        return flags;
    }

    public void unregister(String turnId) {
        local.remove(turnId);
    }

    public TurnFlags flags(String turnId) {
        return local.get(turnId);
    }

    public void publishCancel(String turnId) {
        publishCancel(turnId, "1");
    }

    /**
     * @param reason Redis 值；普通取消为 {@code "1"}，身份失效为 {@code principal_revoked}
     */
    public void publishCancel(String turnId, String reason) {
        String value = reason == null || reason.isBlank() ? "1" : reason;
        redis.opsForValue().set(cancelKey(turnId), value, TTL);
        publish(turnId, "cancel");
        TurnFlags f = local.get(turnId);
        if (f != null) {
            f.cancelRequested.set(true);
            f.cancelReason = "1".equals(value) ? null : value;
        }
    }

    /** 读取取消原因；无键或值为 {@code "1"} 时返回 null。 */
    public String cancelReason(String turnId) {
        TurnFlags f = local.get(turnId);
        if (f != null && f.cancelReason != null) {
            return f.cancelReason;
        }
        String v = redis.opsForValue().get(cancelKey(turnId));
        if (v == null || v.isBlank() || "1".equals(v)) {
            return null;
        }
        return v;
    }

    public void publishSteer(String turnId, String text) {
        String key = steerKey(turnId);
        redis.opsForList().rightPush(key, text);
        redis.expire(key, TTL);
        publish(turnId, "steer");
        TurnFlags f = local.get(turnId);
        if (f != null) {
            f.steerPending.set(true);
        }
    }

    public boolean isCancelFlag(String turnId) {
        TurnFlags f = local.get(turnId);
        if (f != null && f.cancelRequested.get()) {
            return true;
        }
        return Boolean.TRUE.equals(redis.hasKey(cancelKey(turnId)));
    }

    /** LPOP 直到空，返回本轮插话文本。 */
    public List<String> drainSteers(String turnId) {
        List<String> out = new ArrayList<>();
        String key = steerKey(turnId);
        String v;
        while ((v = redis.opsForList().leftPop(key)) != null) {
            out.add(v);
        }
        TurnFlags f = local.get(turnId);
        if (f != null) {
            f.steerPending.set(false);
        }
        return out;
    }

    private void publish(String turnId, String kind) {
        try {
            String payload = mapper.writeValueAsString(Map.of("turnId", turnId, "kind", kind));
            redis.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("publish signal failed turnId={} kind={}", turnId, kind, e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode n = mapper.readTree(message.getBody());
            String turnId = n.path("turnId").asText(null);
            String kind = n.path("kind").asText(null);
            if (turnId == null || kind == null) {
                return;
            }
            TurnFlags f = local.get(turnId);
            if (f == null) {
                return;
            }
            if ("cancel".equals(kind)) {
                f.cancelRequested.set(true);
                String reason = cancelReason(turnId);
                if (reason != null) {
                    f.cancelReason = reason;
                }
            } else if ("steer".equals(kind)) {
                f.steerPending.set(true);
            }
        } catch (Exception e) {
            log.debug("ignore signal message: {}", e.toString());
        }
    }

    public static String cancelKey(String turnId) {
        return "rt:signal:" + turnId + ":cancel";
    }

    public static String steerKey(String turnId) {
        return "rt:signal:" + turnId + ":steer";
    }

    public static final class TurnFlags {
        public final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        public final AtomicBoolean steerPending = new AtomicBoolean(false);
        /** 非普通取消时的 reason（如 principal_revoked）。 */
        public volatile String cancelReason;
    }
}
