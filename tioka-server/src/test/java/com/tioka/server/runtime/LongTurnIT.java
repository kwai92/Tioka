package com.tioka.server.runtime;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-09：时长上限与长任务租约续期。
 */
@Tag("it")
class LongTurnIT {

    @Test
    void maxDurationStops() throws Exception {
        try (DualInstance di = DualInstance.start("max-duration", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            var thr = GateSeed.createThread(di.a(), bundle);
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            RuntimeApi runtime = di.a().getBean(RuntimeApi.class);
            RuntimeApi.TurnHandle handle = principals.runAs(
                    GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                            runtime.startTurn(thr.threadId(), new RuntimeApi.StartTurnCmd(
                                    "cm-max-dur",
                                    List.of(RuntimeApi.InputItem.text("go")),
                                    new RuntimeApi.TurnOptions(20, null, null, Duration.ofNanos(1)),
                                    "chat",
                                    null)));

            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.FAILED, done.status());
            assertEquals("TURN_TIMEOUT", done.errorCode());
            List<RuntimeItemDO> statuses = di.a().getBean(ItemRepository.class)
                    .listByThread(thr.threadId()).stream()
                    .filter(i -> ItemType.STATUS.wire().equals(i.getType()))
                    .toList();
            assertTrue(statuses.stream().anyMatch(i ->
                    i.getContentJson() != null && i.getContentJson().contains("max_duration_reached")));
        }
    }

    @Test
    void twoHourTurnNotReclaimed() throws Exception {
        // 缩短租约：流式 ~ "holding"(7 字符)*400ms ≈ 2.8s，lease-ttl=1s，靠续期不被回收
        try (DualInstance di = DualInstance.start("long-hold", Map.of(
                "tioka.runtime.lease-ttl", "1s",
                "tioka.runtime.lease-renew-interval", "300ms",
                "tioka.runtime.lease-reaper-interval-ms", "400"
        ), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-long-hold", "hold");

            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(),
                    () -> "长任务应靠续期完成而非 LEASE_LOST: " + done.errorCode() + "/" + done.errorMessage());
            assertNull(done.errorCode());
        }
    }
}
