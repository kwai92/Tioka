package com.tioka.runtime.kernel.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.TiokaExecutors;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.application.RuntimeTestHooks;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 经 {@link ToolGateway} 描述与调用工具；有界并行 + 超时（CON-01 注入完整 ToolCallContext）。
 */
@Component
public class GatewayToolPort implements LoopPorts.ToolPort {
    private final ToolGateway gateway;
    private final ObjectMapper mapper;
    private final KernelProperties props;
    private final SignalBus signals;
    private final ObjectProvider<RuntimeTestHooks> testHooks;
    private final PrincipalHolder principals;
    private final ExecutorService pool;

    public GatewayToolPort(ToolGateway gateway,
                           ObjectMapper mapper,
                           KernelProperties props,
                           SignalBus signals,
                           ObjectProvider<RuntimeTestHooks> testHooks,
                           PrincipalHolder principals) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.props = props;
        this.signals = signals;
        this.testHooks = testHooks;
        this.principals = principals;
        int n = Math.max(1, props.getLoop().getToolParallelism());
        this.pool = TiokaExecutors.wrap(Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "rt-tool");
            t.setDaemon(true);
            return t;
        }));
    }

    @Override
    public List<Map<String, Object>> describe(LoopContext ctx) {
        return gateway.asOpenAiTools(toolCtx(ctx));
    }

    @Override
    public List<LoopPorts.ToolOutcome> invokeAll(LoopContext ctx, List<LoopPorts.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        ToolGateway.ToolCallContext toolCtx = toolCtx(ctx);
        long timeoutMs = props.getLoop().getToolTimeout().toMillis();
        // GOV-08：跨 rt-tool 线程池传播 OTel 上下文，使 tool.invoke 挂在 turn.step 下
        Context otelCtx = Context.current();
        List<CompletableFuture<LoopPorts.ToolOutcome>> futures = new ArrayList<>();
        for (LoopPorts.ToolCall c : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                        try (Scope ignored = otelCtx.makeCurrent()) {
                            return invokeOne(toolCtx, c);
                        }
                    }, pool)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .handle((ok, ex) -> {
                        if (ex == null) {
                            return ok;
                        }
                        Throwable root = ex.getCause() == null ? ex : ex.getCause();
                        if (root instanceof RuntimeTestHooks.CrashFrozenAbort abort) {
                            throw abort;
                        }
                        long dur = timeoutMs;
                        if (root instanceof TimeoutException) {
                            return new LoopPorts.ToolOutcome(c.callId(), c.name(), false, "timeout", dur);
                        }
                        String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                        return new LoopPorts.ToolOutcome(c.callId(), c.name(), false, msg, 0);
                    }));
        }
        List<LoopPorts.ToolOutcome> out = new ArrayList<>(calls.size());
        for (CompletableFuture<LoopPorts.ToolOutcome> f : futures) {
            if (signals.isCancelFlag(ctx.turnId())) {
                break;
            }
            out.add(f.join());
        }
        return out;
    }

    private LoopPorts.ToolOutcome invokeOne(ToolGateway.ToolCallContext toolCtx, LoopPorts.ToolCall c) {
        long t0 = System.currentTimeMillis();
        try {
            RuntimeTestHooks hooks = testHooks.getIfAvailable();
            if (hooks != null) {
                hooks.awaitUnfrozenOrInterrupt();
            }
            Map<String, Object> args = mapper.readValue(
                    c.argumentsJson() == null ? "{}" : c.argumentsJson(), new TypeReference<>() {});
            ToolProvider.ToolResult r = gateway.call(toolCtx, c.name(), args);
            return new LoopPorts.ToolOutcome(c.callId(), c.name(), r.ok(), r.content(),
                    System.currentTimeMillis() - t0, null, r.pending(), r.dataLabel());
        } catch (RuntimeTestHooks.CrashFrozenAbort e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new LoopPorts.ToolOutcome(c.callId(), c.name(), false,
                    "arguments 不是合法 JSON：" + e.getMessage(), 0);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new LoopPorts.ToolOutcome(c.callId(), c.name(), false, msg,
                    System.currentTimeMillis() - t0);
        }
    }

    private ToolGateway.ToolCallContext toolCtx(LoopContext ctx) {
        Principal principal = principals.find().orElse(null);
        return ToolCallContexts.forTurn(
                principal,
                ctx.threadId(),
                ctx.turnId(),
                ctx.agentVersionId(),
                ctx.origin(),
                ctx.toolAllowList(),
                ctx.agentToolConfig(),
                ctx.grantId());
    }
}
