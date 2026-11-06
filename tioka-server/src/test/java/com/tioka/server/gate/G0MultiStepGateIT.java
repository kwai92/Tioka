package com.tioka.server.gate;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G0：RT-03。剧本 three-tools.yaml：3 次 tool_call / tool_result + 最终 message，Turn COMPLETED。
 */
@Tag("gate")
@EnabledIfSystemProperty(named = "gate", matches = "true")
class G0MultiStepGateIT {

    @Test
    void threeToolCallsCompleteAutomatically() {
        try (DualInstance di = DualInstance.start()) {
            ConfigurableApplicationContext a = di.a();
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(a, di.llm().baseUrl());
            RuntimeApi.ThreadView thr = GateSeed.createThread(a, bundle);
            RuntimeApi.TurnHandle handle = GateSeed.startTurn(a, bundle, thr.threadId(), "cm-ms-1",
                    "请依次调用 echo 三次");
            RuntimeApi.TurnView done = GateSeed.awaitTerminal(a, bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> "turn failed: " + done.errorMessage());

            PrincipalHolder principals = a.getBean(PrincipalHolder.class);
            RuntimeApi runtime = a.getBean(RuntimeApi.class);
            List<RuntimeApi.ItemView> items = principals.runAs(
                    GateSeed.user(bundle.userId(), bundle.workspaceId()),
                    () -> runtime.listItems(thr.threadId(), 0, 200));

            long toolCalls = items.stream().filter(i -> i.type() == ItemType.TOOL_CALL).count();
            long toolResults = items.stream().filter(i -> i.type() == ItemType.TOOL_RESULT).count();
            long assistantMsgs = items.stream()
                    .filter(i -> i.type() == ItemType.MESSAGE && "assistant".equals(i.role()))
                    .count();
            assertEquals(3, toolCalls, () -> "items=" + items);
            assertEquals(3, toolResults, () -> "items=" + items);
            assertTrue(assistantMsgs >= 1, () -> "items=" + items);
        }
    }
}
