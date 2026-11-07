package com.tioka.server.gate;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.application.TurnSignalService;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import com.tioka.server.gate.support.DualInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G0：RT-05。
 * <ul>
 *   <li>排队 Turn：实例 B 发 interrupt → 共享 DB 上状态 CANCELLED</li>
 *   <li>运行中 Turn：实例 B 写 Redis cancel 信号 → 实例 A 的 {@link SignalBus} 本地标志置位</li>
 * </ul>
 *
 * <p>需 {@code -Dgate=true}。
 */
@Tag("gate")
@EnabledIfSystemProperty(named = "gate", matches = "true")
class G0CancelAcrossInstancesGateIT {

    @Test
    void cancelOnBStopsTurnOnA() {
        try (DualInstance di = DualInstance.start()) {
            ConfigurableApplicationContext a = di.a();
            ConfigurableApplicationContext b = di.b();
            SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(SystemPrincipals.system()));
            try {
                RuntimeThreadRepository sessions = a.getBean(RuntimeThreadRepository.class);
                TurnRepository turns = a.getBean(TurnRepository.class);
                TurnSignalService signalsB = b.getBean(TurnSignalService.class);
                SignalBus busA = a.getBean(SignalBus.class);
                StringRedisTemplate redis = a.getBean(StringRedisTemplate.class);

                String threadId = Ids.newId("thr");
                RuntimeThreadDO s = new RuntimeThreadDO();
                s.setId(threadId);
                s.setStatus("OPEN");
                s.setAgentVersionId("agent_v1");
                s.setWorkspaceId("ws_default");
                s.setOwnerId("0");
                s.setLastItemSeq(0L);
                s.setOrigin("chat");
                s.setDepth(0);
                sessions.upsert(s);

                // 1) 排队取消（跨实例写库）
                RuntimeTurnDO queued = newTurn(turns, threadId, "cm-q");
                turns.insert(queued);
                signalsB.interrupt(queued.getId());
                assertEquals("CANCELLED", turns.findById(queued.getId()).orElseThrow().getStatus());

                // 2) 运行中取消：在 A 注册本地 Turn，B 发信号，A 本地标志置位
                String runningId = Ids.newId("turn");
                RuntimeTurnDO running = newTurn(turns, threadId, "cm-r");
                running.setId(runningId);
                running.setStatus("RUNNING");
                running.setLeaseVersion(1L);
                turns.insert(running);
                busA.register(runningId);

                signalsB.interrupt(runningId);

                Instant deadline = Instant.now().plusSeconds(2);
                boolean ok = false;
                while (Instant.now().isBefore(deadline)) {
                    if (Boolean.TRUE.equals(redis.hasKey(SignalBus.cancelKey(runningId)))
                            && busA.flags(runningId) != null
                            && busA.flags(runningId).cancelRequested.get()) {
                        ok = true;
                        break;
                    }
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                assertTrue(ok, "A 应收到跨实例 cancel 信号");
                busA.unregister(runningId);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private static RuntimeTurnDO newTurn(TurnRepository turns, String threadId, String clientMsgId) {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(Ids.newId("turn"));
        t.setThreadId(threadId);
        t.setSeq(turns.nextSeq(threadId));
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(clientMsgId);
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        return t;
    }
}
