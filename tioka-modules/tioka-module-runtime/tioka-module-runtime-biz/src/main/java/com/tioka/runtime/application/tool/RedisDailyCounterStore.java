package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis 实现：{@code trust:usage:{agentId}:{actionClass}:{yyyyMMdd}} JSON（ADR-032 口径），
 * TTL 覆盖到次日零点（UTC，至少 24 小时）。键缺失 = 当日无记录（零值）；
 * Redis 不可用 = {@link DailyCounterStore.DailyUsage#unknown()}（按已用满，宁可多转审批）。
 */
@Component
public class RedisDailyCounterStore implements DailyCounterStore {

    public static final String KEY_PREFIX = "trust:usage:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final AppliedActionsSource source;
    private final ObjectMapper mapper;

    public RedisDailyCounterStore(StringRedisTemplate redis, AppliedActionsSource source,
                                  ObjectMapper mapper) {
        this.redis = redis;
        this.source = source;
        this.mapper = mapper;
    }

    static String keyOf(String agentId, String actionClass, LocalDate day) {
        return KEY_PREFIX + agentId + ":" + actionClass + ":" + DAY.format(day);
    }

    @Override
    public DailyUsage add(String agentId, String actionClass, LocalDate day,
                          long amountMicros, long records) {
        String key = keyOf(agentId, actionClass, day);
        try {
            DailyUsage current = read(key, false);
            DailyUsage next = new DailyUsage(current.amountMicros() + amountMicros,
                    current.records() + records, false);
            redis.opsForValue().set(key, writeJson(next), ttlUntilTomorrow(day));
            return next;
        } catch (RuntimeException e) {
            return DailyUsage.unknownUsage();
        }
    }

    @Override
    public DailyUsage get(String agentId, String actionClass, LocalDate day) {
        try {
            return read(keyOf(agentId, actionClass, day), false);
        } catch (RuntimeException e) {
            return DailyUsage.unknownUsage();
        }
    }

    @Override
    public DailyUsage rebuild(String agentId, String actionClass, LocalDate day) {
        long amount = 0;
        long records = 0;
        for (AppliedActionsSource.AppliedAction a : source.list(agentId, actionClass, day)) {
            amount += a.amountMicros();
            records += a.records();
        }
        DailyUsage rebuilt = new DailyUsage(amount, records, false);
        try {
            redis.opsForValue().set(keyOf(agentId, actionClass, day), writeJson(rebuilt),
                    ttlUntilTomorrow(day));
        } catch (RuntimeException ignored) {
            // 回填失败：返回重算值，下次再试
        }
        return rebuilt;
    }

    private DailyUsage read(String key, boolean unknownOnMissing) {
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return DailyUsage.zero();
        }
        try {
            JsonNode n = mapper.readTree(json);
            return new DailyUsage(n.path("amountMicros").asLong(0),
                    n.path("records").asLong(0), false);
        } catch (Exception e) {
            return DailyUsage.zero();
        }
    }

    private String writeJson(DailyUsage usage) {
        try {
            // LinkedHashMap 保证键序稳定（审计/排查口径一致）
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("amountMicros", usage.amountMicros());
            payload.put("records", usage.records());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"amountMicros\":0,\"records\":0}";
        }
    }

    /** TTL 48h：覆盖整个自然日并留时区余量，键按日组织自然过期。 */
    private static Duration ttlUntilTomorrow(LocalDate day) {
        return Duration.ofHours(48);
    }
}
