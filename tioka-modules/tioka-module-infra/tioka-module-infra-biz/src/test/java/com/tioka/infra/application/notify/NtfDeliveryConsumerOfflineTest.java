package com.tioka.infra.application.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.infra.api.notify.ChannelCapabilities;
import com.tioka.infra.api.notify.DeliveryRef;
import com.tioka.infra.api.notify.Notice;
import com.tioka.infra.api.notify.NotificationChannel;
import com.tioka.infra.api.notify.Recipient;
import com.tioka.infra.infrastructure.NtfDeliveryRepository;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NtfDeliveryConsumer 离线分支单测（无 Spring 上下文 / 无数据库）：
 * SinkGuard 去向许可 DENY、RECIPIENT_UNMAPPED、CHANNEL_NOT_REGISTERED、
 * 发送成功与重试记账分支；容器侧端到端见 server 模块 NotificationServiceIT。
 */
class NtfDeliveryConsumerOfflineTest {

    private static final String DELIVERY_ID = "01JCNTFTEST0000000000000000";

    private NtfDeliveryRepository repository;
    private RecipientResolver resolver;
    private NotificationChannelRegistry registry;
    private StubSinkGuard sinkGuard;
    private RecordingAlertHook alertHook;
    private PlatformTransactionManager txManager;
    private NtfDeliveryConsumer consumer;

    /** 固定裁决的 SinkGuard 桩（记录调用）。 */
    static final class StubSinkGuard implements SinkGuard {
        SinkCheckResult next = SinkCheckResult.pass();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public SinkCheckResult check(SinkKind kind, String sinkRef, DataLabel label, Object payload) {
            calls.incrementAndGet();
            return next;
        }
    }

    static final class RecordingAlertHook implements NtfDeliveryAlertHook {
        final List<String> failures = new java.util.ArrayList<>();

        @Override
        public void onDeliveryFailed(String deliveryId, String channel, String recipient, String lastError) {
            failures.add(deliveryId + ":" + lastError);
        }
    }

    /** 可编程渠道桩。 */
    static final class StubChannel implements NotificationChannel {
        private final String name;
        private final AtomicInteger sends = new AtomicInteger();
        private final boolean fail;

        StubChannel(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public String channel() {
            return name;
        }

        @Override
        public ChannelCapabilities capabilities() {
            return new ChannelCapabilities(false, false, 4096, false);
        }

        @Override
        public DeliveryRef send(Recipient to, Notice notice) {
            sends.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("channel down");
            }
            return new DeliveryRef(name, name + "-ref-1");
        }

        int sends() {
            return sends.get();
        }
    }

    @BeforeEach
    void setUp() {
        repository = mock(NtfDeliveryRepository.class);
        resolver = mock(RecipientResolver.class);
        sinkGuard = new StubSinkGuard();
        alertHook = new RecordingAlertHook();
        registry = new NotificationChannelRegistry(List.of());
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        consumer = new NtfDeliveryConsumer(repository, resolver, registry,
                sinkGuard, alertHook, new ObjectMapper(), txManager);
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", pending()));
    }

    private static NtfDeliveryDO row(String channel, String status) {
        NtfDeliveryDO row = new NtfDeliveryDO();
        row.setDeliveryId(DELIVERY_ID);
        row.setDedupKey("key:" + channel + ":u1");
        row.setChannel(channel);
        row.setRecipient("u1");
        row.setNoticeJson("{\"noticeKey\":\"key\",\"title\":\"标题\",\"blocks\":[],"
                + "\"actions\":[],\"label\":\"INTERNAL\"}");
        row.setStatus(status);
        row.setAttempts(0);
        return row;
    }

    private static String pending() {
        return NtfDeliveryRepository.STATUS_PENDING;
    }

    private void handle() {
        consumer.handle(envelope(), Map.of("deliveryId", DELIVERY_ID));
    }

    private static EventEnvelope envelope() {
        return new EventEnvelope("evt-1", "tioka.ntf.delivery_requested.v1", 1, null,
                Instant.now().toString(), "test", null, null, null, null, null, null, 0,
                null, Map.of("deliveryId", DELIVERY_ID));
    }

    @Test
    void labelDeniedMarksFailedWithoutSend() {
        sinkGuard.next = SinkCheckResult.deny("超出去向许可");
        StubChannel noop = new StubChannel("noop", false);
        registry = new NotificationChannelRegistry(List.of(noop));
        consumer = new NtfDeliveryConsumer(repository, resolver, registry,
                sinkGuard, alertHook, new ObjectMapper(), txManager);
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", pending()));

        assertDoesNotThrow(this::handle);

        verify(repository).markFailed(DELIVERY_ID, NtfDeliveryConsumer.ERR_LABEL_DENIED);
        assertEquals(1, sinkGuard.calls.get(), "许可判定应执行");
        assertEquals(0, noop.sends(), "被拒通知不得调用渠道发送");
        verify(repository, never()).markSent(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void recipientUnmappedMarksFailedWithoutSend() {
        StubChannel noop = new StubChannel("noop", false);
        registry = new NotificationChannelRegistry(List.of(noop));
        consumer = new NtfDeliveryConsumer(repository, resolver, registry,
                sinkGuard, alertHook, new ObjectMapper(), txManager);
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", pending()));
        when(resolver.resolveRecipient(eq("u1"), eq("noop"))).thenReturn(Optional.empty());

        assertDoesNotThrow(this::handle);

        verify(repository).markFailed(DELIVERY_ID, NtfDeliveryConsumer.ERR_RECIPIENT_UNMAPPED);
        assertEquals(0, noop.sends());
    }

    @Test
    void channelNotRegisteredMarksFailedWithoutRetry() {
        when(resolver.resolveRecipient(eq("u1"), eq("noop")))
                .thenReturn(Optional.of(new Recipient(null, "ext-1")));

        assertDoesNotThrow(this::handle);

        verify(repository).markFailed(DELIVERY_ID, NtfDeliveryConsumer.ERR_CHANNEL_NOT_REGISTERED);
        verify(repository, never()).recordAttempt(anyString(), anyString());
    }

    @Test
    void sendSuccessMarksSentWithExternalRef() {
        StubChannel noop = new StubChannel("noop", false);
        registry = new NotificationChannelRegistry(List.of(noop));
        consumer = new NtfDeliveryConsumer(repository, resolver, registry,
                sinkGuard, alertHook, new ObjectMapper(), txManager);
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", pending()));
        when(resolver.resolveRecipient(eq("u1"), eq("noop")))
                .thenReturn(Optional.of(new Recipient(null, "ext-1")));

        assertDoesNotThrow(this::handle);

        ArgumentCaptor<Instant> sentAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markSent(eq(DELIVERY_ID), eq("noop-ref-1"), sentAt.capture());
        assertEquals(1, noop.sends());
        verify(repository, never()).markFailed(anyString(), anyString());
    }

    @Test
    void transientFailureRethrowsForRedelivery() {
        StubChannel failing = new StubChannel("noop", true);
        registry = new NotificationChannelRegistry(List.of(failing));
        consumer = new NtfDeliveryConsumer(repository, resolver, registry,
                sinkGuard, alertHook, new ObjectMapper(), txManager);
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", pending()));
        when(resolver.resolveRecipient(eq("u1"), eq("noop")))
                .thenReturn(Optional.of(new Recipient(null, "ext-1")));
        when(repository.recordAttempt(eq(DELIVERY_ID), anyString())).thenReturn(1);

        assertThrows(IllegalStateException.class, this::handle);

        verify(repository).recordAttempt(eq(DELIVERY_ID), anyString());
        verify(repository, never()).markFailed(anyString(), anyString());
        verify(repository, never()).markSent(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void exhaustedAttemptsMarkFailedAndFireAlertHook() {
        StubChannel failing = new StubChannel("noop", true);
        registry = new NotificationChannelRegistry(List.of(failing));
        consumer = new NtfDeliveryConsumer(repository, resolver, registry,
                sinkGuard, alertHook, new ObjectMapper(), txManager);
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", pending()));
        when(resolver.resolveRecipient(eq("u1"), eq("noop")))
                .thenReturn(Optional.of(new Recipient(null, "ext-1")));
        when(repository.recordAttempt(eq(DELIVERY_ID), anyString()))
                .thenReturn(NtfDeliveryConsumer.MAX_ATTEMPTS);

        assertDoesNotThrow(this::handle);

        verify(repository).markFailed(eq(DELIVERY_ID), anyString());
        assertEquals(1, alertHook.failures.size(), "3 次耗尽应触发告警钩子");
        assertEquals(0, alertHook.failures.get(0).indexOf(DELIVERY_ID + ":"));
    }

    @Test
    void nonPendingRowsAreSkipped() {
        when(repository.findById(DELIVERY_ID)).thenReturn(row("noop", "SENT"));

        assertDoesNotThrow(this::handle);

        verify(repository, never()).markFailed(anyString(), anyString());
        verify(resolver, never()).resolveRecipient(anyString(), anyString());
    }
}
