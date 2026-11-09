package com.tioka.system.application.quota;

import com.tioka.system.api.notify.NotificationPort;
import com.tioka.system.api.spi.QuotaThresholdExceededEvent;
import com.tioka.system.infrastructure.GovQuotaRepository;
import com.tioka.system.infrastructure.persistence.GovQuotaDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 / GAP-L5-3：月度 token 80% 预警——发布、去重、订阅消费全链离线单测
 *（Redis 用内存 KV 模拟，语义等价：get/increment/setIfAbsent）。
 */
class QuotaWarnTest {

    private static final String WS = "ws1";
    private static final long LIMIT = 1_000L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private Map<String, String> kv;
    private ApplicationEventPublisher publisher;
    private DefaultQuotaGuard guard;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        kv = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> kv.get(inv.getArgument(0, String.class)));
        when(ops.increment(anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            long v = Long.parseLong(kv.getOrDefault(key, "0")) + inv.getArgument(1, Long.class);
            kv.put(key, String.valueOf(v));
            return v;
        });
        // 模拟 SETNX：key 已存在返回 null（失败），否则写入并返回成功
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            return kv.putIfAbsent(key, inv.getArgument(1, String.class)) == null;
        });

        GovQuotaRepository repo = mock(GovQuotaRepository.class);
        when(repo.find(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            GovQuotaDO row = new GovQuotaDO();
            row.setLimitValue(LIMIT);
            return row;
        });

        publisher = mock(ApplicationEventPublisher.class);
        guard = new DefaultQuotaGuard(redis, repo, publisher);
    }

    @Test
    void publishesWarningOncePerScopeWhenCrossing80Pct() {
        guard.consumeTokens(WS, "u1", "a1", 900L);
        // 首次越线：workspace / user / agent 三个 scope 各发一次
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(3)).publishEvent(captor.capture());
        List<QuotaThresholdExceededEvent> events = captor.getAllValues().stream()
                .map(e -> (QuotaThresholdExceededEvent) e).toList();
        assertEquals(3, events.size());
        assertEquals(900L, events.get(0).usedValue());
        assertEquals(LIMIT, events.get(0).limitValue());
        assertEquals(80, events.get(0).thresholdPct());

        // 同月继续消费：去重键已存在，不再发布
        guard.consumeTokens(WS, "u1", "a1", 50L);
        verify(publisher, times(3)).publishEvent(any(Object.class));
    }

    @Test
    void noWarningWhenBelowThreshold() {
        guard.consumeTokens(WS, "u1", "a1", 700L);
        verify(publisher, times(0)).publishEvent(any(Object.class));
    }

    @Test
    void noWarningWhenNoLimitConfigured() {
        GovQuotaRepository repo = mock(GovQuotaRepository.class);
        when(repo.find(anyString(), anyString(), anyString())).thenReturn(null);
        DefaultQuotaGuard noLimit = new DefaultQuotaGuard(redis, repo, publisher);
        noLimit.consumeTokens(WS, "u1", "a1", 500L);
        verify(publisher, times(0)).publishEvent(any(Object.class));
    }

    @Test
    void notifierSendsNotificationWithDedupKey() {
        NotificationPort port = mock(NotificationPort.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<NotificationPort> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        QuotaWarnNotifier notifier = new QuotaWarnNotifier(provider);

        QuotaThresholdExceededEvent event = new QuotaThresholdExceededEvent(
                "workspace", WS, "tokens_month", LIMIT, 900L, 80, "2026-11");
        notifier.onQuotaThreshold(event);

        ArgumentCaptor<NotificationPort.NotificationCommand> captor =
                ArgumentCaptor.forClass(NotificationPort.NotificationCommand.class);
        verify(port).notify(captor.capture());
        assertEquals("quota", captor.getValue().channel());
        assertEquals("quota-warn:workspace:ws1:2026-11:80", captor.getValue().dedupKey());
    }

    @Test
    void notifierSilentWithoutPortAndToleratesFailure() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<NotificationPort> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        QuotaWarnNotifier notifier = new QuotaWarnNotifier(provider);
        QuotaThresholdExceededEvent event = new QuotaThresholdExceededEvent(
                "user", "u1", "tokens_month", LIMIT, 900L, 80, "2026-11");
        // 端口未接线：静默
        assertDoesNotThrow(() -> notifier.onQuotaThreshold(event));

        NotificationPort failing = mock(NotificationPort.class);
        org.mockito.Mockito.doThrow(new RuntimeException("im down")).when(failing)
                .notify(any(NotificationPort.NotificationCommand.class));
        when(provider.getIfAvailable()).thenReturn(failing);
        // 通知失败不外抛
        assertDoesNotThrow(() -> notifier.onQuotaThreshold(event));
    }
}
