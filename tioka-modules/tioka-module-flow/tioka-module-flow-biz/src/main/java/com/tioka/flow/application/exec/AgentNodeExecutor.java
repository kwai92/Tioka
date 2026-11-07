package com.tioka.flow.application.exec;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnAwaiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MH-04 / RT-08：Agent 节点经 {@link RuntimeApi} + {@link TurnAwaiter}，禁止直连模型 HTTP。
 */
@Component
public class AgentNodeExecutor implements NodeExecutor {
    private final ObjectProvider<RuntimeApi> runtimeApi;
    private final ObjectProvider<TurnAwaiter> turnAwaiter;

    public AgentNodeExecutor(ObjectProvider<RuntimeApi> runtimeApi,
                             ObjectProvider<TurnAwaiter> turnAwaiter) {
        this.runtimeApi = runtimeApi;
        this.turnAwaiter = turnAwaiter;
    }

    @Override
    public String type() { return "Agent"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String user = extractText(input);
        RuntimeApi runtime = runtimeApi.getIfAvailable();
        TurnAwaiter awaiter = turnAwaiter.getIfAvailable();
        if (runtime == null || awaiter == null) {
            return Map.of("content", "agent:" + user, "role", "agent", "mock", true);
        }
        Object rawVersion = config.get("agentVersionId");
        String agentVersionId = rawVersion == null ? null : String.valueOf(rawVersion);
        if (agentVersionId == null || agentVersionId.isBlank() || "null".equals(agentVersionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Agent 节点缺少 agentVersionId");
        }
        String nodeId = node.get("id") == null ? "" : String.valueOf(node.get("id"));
        RuntimeApi.ThreadView thread = runtime.createThread(new RuntimeApi.CreateThreadCmd(
                agentVersionId, "flow", ctx.runId(), null,
                Map.of("runId", ctx.runId(), "nodeId", nodeId)));
        RuntimeApi.TurnHandle handle = runtime.startTurn(thread.threadId(), new RuntimeApi.StartTurnCmd(
                Ids.newId("cm"),
                List.of(RuntimeApi.InputItem.text(user)),
                null,
                "flow",
                nodeId));
        RuntimeApi.TurnView view = awaiter.await(handle.turnId(), Duration.ofMinutes(5));
        String text = awaiter.finalText(handle.turnId()).orElse("");
        return Map.of(
                "content", text,
                "role", "agent",
                "turnId", handle.turnId(),
                "turnStatus", view.status().name());
    }

    private static String extractText(Object input) {
        if (input == null) return "";
        if (input instanceof Map<?, ?> m) {
            Object t = m.containsKey("text") ? m.get("text") : m.get("content");
            return t == null ? String.valueOf(input) : String.valueOf(t);
        }
        return String.valueOf(input);
    }
}
