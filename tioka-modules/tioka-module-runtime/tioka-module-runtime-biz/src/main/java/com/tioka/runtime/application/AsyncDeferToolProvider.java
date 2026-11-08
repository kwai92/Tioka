package com.tioka.runtime.application;

import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.kernel.child.ChildPendingRegistry;
import com.tioka.runtime.kernel.child.RuntimeChildRouterConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 闸门桩：{@code async.defer} 立即返回 {@link PendingJob}，供 AsyncToolIT 验证 WAITING_CHILD（RT-09）。
 * <p>生产工具不得改成异步；仅此桩与测试辅助。
 */
@Component
public class AsyncDeferToolProvider implements ToolProvider {
    public static final String TOOL_NAME = "async.defer";

    private final EventPublisher eventPublisher;
    private final ChildPendingRegistry registry;
    private final ObjectProvider<RuntimeChildRouterConsumer> childRouter;
    /** jobId → 可选自动完成输出（由 args.autoCompleteMs 标记，实际完成由 IT/complete 触发）。 */
    private final Map<String, String> autoOutputByJob = new ConcurrentHashMap<>();

    public AsyncDeferToolProvider(EventPublisher eventPublisher,
                                  ChildPendingRegistry registry,
                                  ObjectProvider<RuntimeChildRouterConsumer> childRouter) {
        this.eventPublisher = eventPublisher;
        this.registry = registry;
        this.childRouter = childRouter;
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return List.of(ToolDescriptor.builder()
                .name(TOOL_NAME)
                .description("Gate stub: accept async job and return PendingJob (RT-09)")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timeoutSec", Map.of("type", "integer"),
                                "output", Map.of("type", "string")),
                        "required", List.of()))
                .build());
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        if (!TOOL_NAME.equals(toolName)) {
            return new ToolResult(false, "unknown tool");
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String jobId = Ids.newId("job");
        int timeoutSec = intOf(args.get("timeoutSec"), 120);
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSec));
        String output = stringOf(args.get("output"));
        if (output != null && !output.isBlank()) {
            autoOutputByJob.put(jobId, output);
        }
        return new ToolResult(true, "accepted", new PendingJob("async.defer", jobId, timeout));
    }

    /** 按 jobId 完成（registry 已由 ChildWaitService 登记 turn/call）。 */
    public void complete(String jobId, boolean ok, Object output, String errorCode) {
        ChildPendingRegistry.Entry meta = registry.take(jobId).orElse(null);
        if (meta == null) {
            return;
        }
        Object out = output;
        if (out == null || "".equals(out)) {
            String preferred = autoOutputByJob.remove(jobId);
            if (preferred != null) {
                out = preferred;
            }
        } else {
            autoOutputByJob.remove(jobId);
        }
        publishAndRoute(meta.turnId(), meta.callId(), jobId, ok, out, errorCode);
    }

    /** 按 turnId+callId 完成（IT 主路径）。 */
    public void completeByCall(String turnId, String callId, boolean ok, Object output, String errorCode) {
        publishAndRoute(turnId, callId, callId, ok, output, errorCode);
    }

    private void publishAndRoute(String turnId, String callId, String eventKey,
                                 boolean ok, Object output, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turnId", turnId);
        payload.put("callId", callId);
        payload.put("ok", ok);
        payload.put("output", output == null ? "" : output);
        payload.put("errorCode", errorCode == null ? "" : errorCode);
        try {
            eventPublisher.publish(new DomainEvent(
                    RuntimeMqTopics.CHILD_FINISHED,
                    RuntimeMqTopics.TOPIC,
                    eventKey,
                    payload,
                    0,
                    null,
                    "child:" + eventKey));
        } catch (RuntimeException e) {
            // IT/local 无事务时 Outbox 为 MANDATORY；本地路由仍须执行
            if (!(e instanceof org.springframework.transaction.IllegalTransactionStateException)
                    && !(e.getCause() instanceof org.springframework.transaction.IllegalTransactionStateException)) {
                throw e;
            }
        }
        RuntimeChildRouterConsumer router = childRouter.getIfAvailable();
        if (router != null) {
            router.routeLocally(turnId, callId, ok, output, errorCode);
        }
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o));
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
