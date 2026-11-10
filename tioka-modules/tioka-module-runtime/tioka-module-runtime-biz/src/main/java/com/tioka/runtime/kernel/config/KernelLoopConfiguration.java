package com.tioka.runtime.kernel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.security.TiokaExecutors;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.approval.ToolApprovalCoordinator;
import com.tioka.runtime.kernel.child.ChildWaitService;
import com.tioka.runtime.kernel.application.loop.OutputStage;
import com.tioka.runtime.kernel.domain.AgentLoop;
import com.tioka.runtime.kernel.domain.ContextBuilder;
import com.tioka.runtime.kernel.domain.ContextSegmentRegistry;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        // 固定池：队列未满时也必须能拉满 maxConcurrentTurns（否则闸门并发会被 core 卡住）
        return TiokaExecutors.wrap(new ThreadPoolExecutor(
                n, n, 60, TimeUnit.SECONDS,
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
    ContextBuilder contextBuilder(ObjectMapper mapper, ContextSegmentRegistry segments) {
        return new ContextBuilder(mapper, segments);
    }

    @Bean
    AgentLoop agentLoop(LoopPorts.ModelPort modelPort,
                        LoopPorts.ToolPort toolPort,
                        LoopPorts.ItemSink sink,
                        ContextBuilder contextBuilder,
                        TurnRepository turns,
                        ItemRepository items,
                        ObjectMapper mapper,
                        ToolApprovalCoordinator approvals,
                        ChildWaitService childWait,
                        ObjectProvider<AgentSnapshotApi> agentSnapshots,
                        ObjectProvider<SchemaApi> schemaApis) {
        // SCH-03：OutputStage 依赖弱装配——快照/Schema 未启用时契约解析为 null，OutputStage 直通
        OutputStage outputStage = new OutputStage(mapper,
                agentVersionId -> {
                    if (agentVersionId == null || agentVersionId.isBlank()) {
                        return null;
                    }
                    AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
                    if (snapshots == null) {
                        return null;
                    }
                    return snapshots.get(agentVersionId).spec().output();
                },
                schemaApis.getIfAvailable(),
                modelPort, contextBuilder, items::listByThread, sink);
        return new AgentLoop(modelPort, toolPort, sink, contextBuilder, turns,
                items::listByThread, mapper, approvals, childWait, outputStage);
    }
}
