package com.tioka.infra.application.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.infra.infrastructure.NtfDeliveryRepository;
import com.tioka.infra.infrastructure.mapper.NtfDeliveryMapper;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;
import com.tioka.system.api.notify.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2 NotificationPort → NTF-01 NotificationService 桥接离线单测：
 * dedupKey 作 noticeKey、template/variables 最小映射、渠道列表透传。
 */
class NotificationPortBridgeTest {

    private NtfDeliveryMapper mapper;
    private NtfDeliveryRepository repository;
    private RecordingPublisher publisher;
    private NotificationService service;
    private NotificationPortBridge bridge;

    /** 记录 publish 事件的桩（OutboxEventPublisher 为 infra 装配细节，离线不需要）。 */
    static final class RecordingPublisher implements EventPublisher {
        final List<DomainEvent> events = new java.util.ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    @BeforeEach
    void setUp() {
        mapper = mock(NtfDeliveryMapper.class);
        when(mapper.insertIfAbsent(any(NtfDeliveryDO.class))).thenReturn(1);
        repository = new NtfDeliveryRepository(mapper);
        publisher = new RecordingPublisher();
        service = new NotificationService(repository, publisher, new ObjectMapper());
        bridge = new NotificationPortBridge(service, List.of("noop"));
    }

    @Test
    void bridgeForwardsCommandAsNotice() {
        bridge.notify(new NotificationPort.NotificationCommand(
                "quota", "u1", "quota.warn", Map.of("used", 900, "limit", 1000),
                "quota-warn:ws1:2026-11"));

        ArgumentCaptor<NtfDeliveryDO> captor = ArgumentCaptor.forClass(NtfDeliveryDO.class);
        verify(mapper).insertIfAbsent(captor.capture());
        NtfDeliveryDO row = captor.getValue();
        // dedupKey 作 noticeKey 前缀 → dedup_key = {noticeKey}:{channel}:{recipient}
        assertEquals("quota-warn:ws1:2026-11:noop:u1", row.getDedupKey());
        assertEquals("noop", row.getChannel());
        assertEquals("u1", row.getRecipient());
        assertEquals("PENDING", row.getStatus());
        assertTrue(row.getNoticeJson().contains("quota.warn"), "title 应取 template");
        assertTrue(row.getNoticeJson().contains("\"INTERNAL\""), "桥接通知按 INTERNAL 标签");
        assertEquals(1, publisher.events.size());
        assertEquals("tioka.ntf.delivery_requested.v1", publisher.events.get(0).event());
    }

    @Test
    void bridgeUsesTemplateAsNoticeKeyWhenDedupKeyBlank() {
        bridge.notify(new NotificationPort.NotificationCommand(
                "orch", "owner-1", "orch.rule.auto_paused", Map.of(), null));

        ArgumentCaptor<NtfDeliveryDO> captor = ArgumentCaptor.forClass(NtfDeliveryDO.class);
        verify(mapper).insertIfAbsent(captor.capture());
        assertEquals("orch.rule.auto_paused:noop:owner-1", captor.getValue().getDedupKey());
    }

    @Test
    void bridgeDedupsRepeatCommand() {
        NotificationPort.NotificationCommand command = new NotificationPort.NotificationCommand(
                "quota", "u1", "quota.warn", Map.of(), "quota-warn:ws1");
        bridge.notify(command);
        when(mapper.insertIfAbsent(any(NtfDeliveryDO.class))).thenReturn(0);
        bridge.notify(command);

        verify(mapper, times(2)).insertIfAbsent(any(NtfDeliveryDO.class));
        assertEquals(1, publisher.events.size(), "去重命中不重复发事件");
    }
}
