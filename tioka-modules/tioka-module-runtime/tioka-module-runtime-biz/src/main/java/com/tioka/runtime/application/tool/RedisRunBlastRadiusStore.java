package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 实现：{@code trust:blast:{turnId}:{actionClass}} JSON，TTL 2h（Run 结束后过期）。
 * 与 {@link RedisDailyCounterStore} 同构；不复用日累计键，避免撤销扣回影响 Run 维度。
 */
@Component
public class RedisRunBlastRadiusStore implements RunBlastRadiusStore {

    public static final String KEY_PREFIX = "trust:blast:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisRunBlastRadiusStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    static String keyOf(String scopeId, String actionClass) {
        return KEY_PREFIX + scopeId + ":" + actionClass;
    }

    @Override
    public Usage add(String scopeId, String actionClass, long amountMicros, long records) {
        if (scopeId == null || scopeId.isBlank()) {
            return new Usage(amountMicros, records, false);
        }
        String key = keyOf(scopeId, actionClass == null ? "" : actionClass);
        try {
            String json = redis.opsForValue().get(key);
            long amount = amountMicros;
            long count = records;
            if (json != null && !json.isBlank()) {
                JsonNode n = mapper.readTree(json);
                amount += n.path("amountMicros").asLong(0);
                count += n.path("records").asLong(0);
            }
            redis.opsForValue().set(key, writeJson(amount, count), TTL);
            return new Usage(amount, count, false);
        } catch (Exception e) {
            // Redis 或 JSON 异常：按「宁可多转审批」处理
            return Usage.unknownUsage();
        }
    }

    private String writeJson(long amount, long records) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("amountMicros", amount);
            payload.put("records", records);
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"amountMicros\":0,\"records\":0}";
        }
    }
}
