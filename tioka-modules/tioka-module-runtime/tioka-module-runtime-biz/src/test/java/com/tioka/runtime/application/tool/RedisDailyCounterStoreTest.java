package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 当日累计 Redis 存储离线测试（TRU-02 / M3-05 §3.3，ADR-032 键口径）：
 * {@code trust:usage:{agentId}:{actionClass}:{yyyyMMdd}} JSON；add 累加、get 缺失零值、
 * rebuild 从 trust_action 重算回填；Redis 不可用 → unknownUsage（按已用满，宁可多转审批）。
 */
class RedisDailyCounterStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DailyCounterStore.AppliedActionsSource source;
    private RedisDailyCounterStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        source = mock(DailyCounterStore.AppliedActionsSource.class);
        store = new RedisDailyCounterStore(redis, source, new ObjectMapper());
    }

    @Test
    void keyFollowsAdr032Layout() {
        assertEquals("trust:usage:ag1:pay:20261110",
                RedisDailyCounterStore.keyOf("ag1", "pay", DAY));
    }

    @Test
    void getMissingKeyReturnsZero() {
        when(ops.get("trust:usage:ag1:pay:20261110")).thenReturn(null);
        DailyCounterStore.DailyUsage u = store.get("ag1", "pay", DAY);
        assertEquals(0, u.amountMicros());
        assertEquals(0, u.records());
        assertEquals(false, u.unknown());
    }

    @Test
    void getInvalidJsonReturnsZero() {
        when(ops.get(anyString())).thenReturn("not-json{");
        DailyCounterStore.DailyUsage u = store.get("ag1", "pay", DAY);
        assertEquals(0, u.amountMicros());
    }

    @Test
    void addAccumulatesAndWritesJson() {
        when(ops.get("trust:usage:ag1:pay:20261110"))
                .thenReturn("{\"amountMicros\":1000,\"records\":2}");
        DailyCounterStore.DailyUsage u = store.add("ag1", "pay", DAY, 500L, 3);
        assertEquals(1500, u.amountMicros());
        assertEquals(5, u.records());
        verify(ops).set(eq("trust:usage:ag1:pay:20261110"),
                eq("{\"amountMicros\":1500,\"records\":5}"), eq(java.time.Duration.ofHours(48)));
    }

    @Test
    void addFromEmptyStartsAtZero() {
        when(ops.get(anyString())).thenReturn(null);
        DailyCounterStore.DailyUsage u = store.add("ag1", "pay", DAY, 10000L, 1);
        assertEquals(10000, u.amountMicros());
        assertEquals(1, u.records());
    }

    @Test
    void redisFailureOnAddReturnsUnknownUsage() {
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        DailyCounterStore.DailyUsage u = store.add("ag1", "pay", DAY, 1, 1);
        assertEquals(true, u.unknown(), "Redis 不可用按已用满（宁可多转审批）");
    }

    @Test
    void redisFailureOnGetReturnsUnknownUsage() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        DailyCounterStore.DailyUsage u = store.get("ag1", "pay", DAY);
        assertEquals(true, u.unknown());
        assertEquals(Long.MAX_VALUE / 4, u.amountMicros());
    }

    @Test
    void rebuildSumsAppliedActionsAndBackfills() {
        when(source.list("ag1", "pay", DAY)).thenReturn(List.of(
                new DailyCounterStore.AppliedActionsSource.AppliedAction(1000L, 2),
                new DailyCounterStore.AppliedActionsSource.AppliedAction(2500L, 3)));
        DailyCounterStore.DailyUsage u = store.rebuild("ag1", "pay", DAY);
        assertEquals(3500, u.amountMicros());
        assertEquals(5, u.records());
        assertEquals(false, u.unknown());
        verify(ops).set(eq("trust:usage:ag1:pay:20261110"),
                eq("{\"amountMicros\":3500,\"records\":5}"), eq(java.time.Duration.ofHours(48)));
    }

    @Test
    void rebuildBackfillFailureStillReturnsRebuiltValue() {
        when(source.list(anyString(), anyString(), any())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
        DailyCounterStore.DailyUsage u = store.rebuild("ag1", "pay", DAY);
        assertEquals(0, u.amountMicros(), "回填失败返回重算值，下次再试");
        assertEquals(false, u.unknown());
    }

    @Test
    void rebuildWithEmptySourceWritesZero() {
        when(source.list(anyString(), anyString(), any())).thenReturn(List.of());
        DailyCounterStore.DailyUsage u = store.rebuild("ag1", "pay", DAY);
        assertEquals(0, u.amountMicros());
        assertEquals(0, u.records());
        assertTrue(true);
    }
}
