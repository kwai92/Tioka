package com.tioka.runtime.kernel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.TiokaExecutors;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.domain.AgentLoop;
import com.tioka.runtime.kernel.domain.ContextBuilder;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class KernelLoopConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService turnExecutor(KernelProperties props) {
        int n = props.getExecutor().getMaxConcurrentTurns();
        int q = props.getExecutor().getQueueCapacity();
        return TiokaExecutors.wrap(new ThreadPoolExecutor(
                Math.min(8, n), n, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(q),
                r -> {
                    Thread t = new Thread(r, "rt-turn");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()));
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService turnLeaseRenewScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "rt-lease-renew");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    ContextBuilder contextBuilder(ObjectMapper mapper) {
        return new ContextBuilder(mapper);
    }

    /** M0 桩：无工具、直接返回固定文本；RT-03 后续换 LegacyModelPort / ToolGateway。 */
    @Bean
    LoopPorts.ModelPort stubModelPort() {
        return (ctx, messages, tools, onDelta) ->
                new LoopPorts.ModelPort.Result("ok", null, List.of(), 1, 1, "stub");
    }

    @Bean
    LoopPorts.ToolPort stubToolPort() {
        return new LoopPorts.ToolPort() {
            @Override
            public List<Map<String, Object>> describe(com.tioka.runtime.kernel.domain.LoopContext ctx) {
                return List.of();
            }

            @Override
            public List<LoopPorts.ToolOutcome> invokeAll(
                    com.tioka.runtime.kernel.domain.LoopContext ctx, List<LoopPorts.ToolCall> calls) {
                return List.of();
            }
        };
    }

    @Bean
    AgentLoop agentLoop(LoopPorts.ModelPort modelPort,
                        LoopPorts.ToolPort toolPort,
                        LoopPorts.ItemSink sink,
                        ContextBuilder contextBuilder,
                        TurnRepository turns,
                        ItemRepository items,
                        ObjectMapper mapper) {
        return new AgentLoop(modelPort, toolPort, sink, contextBuilder, turns,
                items::listBySession, mapper);
    }
}
