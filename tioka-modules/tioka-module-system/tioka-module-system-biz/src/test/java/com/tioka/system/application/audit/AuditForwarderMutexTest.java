package com.tioka.system.application.audit;

import com.tioka.system.api.spi.AuditSink;
import com.tioka.system.config.AuditProperties;
import com.tioka.system.infrastructure.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-05：审计外送互斥与水位离线单测（mock Repository）。
 * 覆盖：锁被占跳过 / 取锁转发并推进水位 / 外送失败中断不推水位 / 异常时仍释放锁。
 */
class AuditForwarderMutexTest {

    private AuditEventRepository repository;
    private AuditSink sink;
    private AuditForwarder forwarder;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        sink = mock(AuditSink.class);
        forwarder = new AuditForwarder(repository, sink, new AuditProperties());
    }

    private static Map<String, Object> row(String id) {
        return Map.of("id", id, "action", "agent.create", "actorId", "u1");
    }

    @Test
    void skipsWhenLockHeldByAnotherInstance() {
        when(repository.tryAdvisoryLock(AuditForwarder.FORWARD_LOCK_KEY)).thenReturn(false);

        forwarder.forwardBatch();

        verify(repository, never()).listUnforwarded(anyInt());
        verify(repository, never()).markForwarded(anyList());
        verify(repository, never()).advisoryUnlock(anyString());
    }

    @Test
    void forwardsAndAdvancesWatermarkWhenLockAcquired() {
        when(repository.tryAdvisoryLock(AuditForwarder.FORWARD_LOCK_KEY)).thenReturn(true);
        when(repository.listUnforwarded(100)).thenReturn(List.of(row("ev_1"), row("ev_2")));
        doAnswer(inv -> null).when(repository).markForwarded(anyList());

        forwarder.forwardBatch();

        verify(sink, org.mockito.Mockito.times(2)).emit(any(), any(), any());
        verify(repository).markForwarded(List.of("ev_1", "ev_2"));
        // 水位 = 本批最后一条 ID
        verify(repository).updateForwardProgress("siem", "ev_2", 2);
        // 释放锁
        verify(repository).advisoryUnlock(AuditForwarder.FORWARD_LOCK_KEY);
    }

    @Test
    void sinkFailureStopsBeforeWatermark() {
        when(repository.tryAdvisoryLock(AuditForwarder.FORWARD_LOCK_KEY)).thenReturn(true);
        when(repository.listUnforwarded(100)).thenReturn(List.of(row("ev_1"), row("ev_2")));
        org.mockito.Mockito.doThrow(new RuntimeException("siem down"))
                .when(sink).emit(any(), any(), any());

        forwarder.forwardBatch();

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).markForwarded(captor.capture());
        assertTrue(captor.getValue().isEmpty(), "首条失败即中断，无成功记录");
        verify(repository, never()).updateForwardProgress(any(), any(), anyInt());
        verify(repository).advisoryUnlock(AuditForwarder.FORWARD_LOCK_KEY);
    }

    @Test
    void lockReleasedEvenWhenRepositoryThrows() {
        when(repository.tryAdvisoryLock(AuditForwarder.FORWARD_LOCK_KEY)).thenReturn(true);
        when(repository.listUnforwarded(100)).thenThrow(new RuntimeException("db down"));

        try {
            forwarder.forwardBatch();
        } catch (RuntimeException ignored) {
            // 事务回滚由框架处理；此处验证 finally 语义
        }
        verify(repository).advisoryUnlock(AuditForwarder.FORWARD_LOCK_KEY);
    }

    @Test
    void emptyBatchReleasesLockWithoutWatermark() {
        AtomicBoolean unlocked = new AtomicBoolean(false);
        when(repository.tryAdvisoryLock(AuditForwarder.FORWARD_LOCK_KEY)).thenReturn(true);
        when(repository.listUnforwarded(100)).thenReturn(List.of());
        when(repository.advisoryUnlock(AuditForwarder.FORWARD_LOCK_KEY))
                .thenAnswer(inv -> unlocked.compareAndSet(false, true));

        forwarder.forwardBatch();

        verify(repository, never()).updateForwardProgress(any(), any(), anyInt());
        assertTrue(unlocked.get());
    }
}
