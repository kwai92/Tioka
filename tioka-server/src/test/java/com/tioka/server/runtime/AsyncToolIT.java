package com.tioka.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.application.AsyncDeferToolProvider;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.child.ChildTimeoutSweeper;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RT-09：异步工具 → WAITING_CHILD → 续跑 / 超时。
 */
@Tag("it")
class AsyncToolIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void waitingChildResumes() throws Exception {
        try (DualInstance di = DualInstance.start("async-defer", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-async-ok", "defer please");

            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_CHILD);
            String callId = pendingCallId(di, thr.threadId());
            assertNotNull(callId);

            di.a().getBean(AsyncDeferToolProvider.class)
                    .completeByCall(handle.turnId(), callId, true, "async-ok", null);

            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
            List<RuntimeItemDO> results = di.a().getBean(ItemRepository.class)
                    .listByThread(thr.threadId()).stream()
                    .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                    .toList();
            assertTrue(results.stream().anyMatch(i -> i.getContentJson().contains("\"resolves\"")));
            assertTrue(results.stream().anyMatch(i -> i.getContentJson().contains("async-ok")));
        }
    }

    @Test
    void childTimeout() throws Exception {
        try (DualInstance di = DualInstance.start("async-defer-timeout", Map.of(
                "tioka.runtime.child-timeout-sweeper-interval-ms", "500"
        ), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(), "cm-async-to", "defer timeout");

            GateSeed.awaitStatus(di.a(), bundle, handle.turnId(), 30_000, TurnStatus.WAITING_CHILD);
            // 主动扫一次，避免只靠调度抖动
            Thread.sleep(1200);
            di.a().getBean(ChildTimeoutSweeper.class).sweep();

            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
            List<RuntimeItemDO> results = di.a().getBean(ItemRepository.class)
                    .listByThread(thr.threadId()).stream()
                    .filter(i -> ItemType.TOOL_RESULT.wire().equals(i.getType()))
                    .toList();
            assertTrue(results.stream().anyMatch(i ->
                    i.getContentJson().contains("CHILD_TIMEOUT")
                            || i.getContentJson().contains("子任务超时")));
        }
    }

    private String pendingCallId(DualInstance di, String threadId) throws Exception {
        for (RuntimeItemDO i : di.a().getBean(ItemRepository.class).listByThread(threadId)) {
            if (!ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                continue;
            }
            JsonNode root = mapper.readTree(i.getContentJson());
            if (root.has("pending")) {
                return root.path("callId").asText(null);
            }
        }
        return null;
    }
}
