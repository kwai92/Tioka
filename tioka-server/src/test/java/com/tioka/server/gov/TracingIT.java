package com.tioka.server.gov;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.infra.messaging.IdempotentDeliveryService;
import com.tioka.infra.messaging.infrastructure.mapper.SysOutboxMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.support.IntegrationTestSupport;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-08：手动 span 父子关系与 outbox {@code traceparent} 跨异步边界传播。
 * 使用 {@link InMemorySpanExporter}，不启 Java Agent。
 */
@SpringBootTest
@Tag("it")
@Import(TracingIT.TestBeans.class)
class TracingIT {

    private static InMemorySpanExporter exporter;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired EventPublisher publisher;
    @Autowired PrincipalHolder principals;
    @Autowired TransactionTemplate tx;
    @Autowired IdempotentDeliveryService delivery;
    @Autowired SysOutboxMapper outboxMapper;
    @Autowired TracingConsumer tracingConsumer;

    @BeforeAll
    static void installOtel() {
        GlobalOpenTelemetry.resetForTest();
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .buildAndRegisterGlobal();
    }

    @AfterAll
    static void clearOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @BeforeEach
    void resetSpans() {
        exporter.reset();
        tracingConsumer.clear();
    }

    @Test
    void turnWithToolCallsHasSpanTree() throws Exception {
        try (DualInstance di = DualInstance.start("three-tools", Map.of(), false)) {
            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            var thr = GateSeed.createThread(di.a(), bundle);
            var handle = GateSeed.startTurn(di.a(), bundle, thr.threadId(),
                    "cm-tracing-" + System.nanoTime(), "请依次调用 echo 三次");
            var done = GateSeed.awaitTerminal(di.a(), bundle, handle.turnId(), 60_000);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        // DualInstance/种子可能产生多条 turn.run；勿取 findFirst，以「有 turn.step 子节点」的那条为准
        Set<String> turnRunIds = spans.stream()
                .filter(s -> "turn.run".equals(s.getName()))
                .map(SpanData::getSpanId)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(turnRunIds.isEmpty(), () -> "应有 turn.run: " + names(spans));
        SpanData turnStep = spans.stream()
                .filter(s -> "turn.step".equals(s.getName()) && turnRunIds.contains(s.getParentSpanId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("turn.step 应以某条 turn.run 为父: " + names(spans)));
        SpanData turnRun = spans.stream()
                .filter(s -> s.getSpanId().equals(turnStep.getParentSpanId()))
                .findFirst()
                .orElseThrow();
        String traceId = turnRun.getTraceId();
        assertTrue(spans.stream().anyMatch(s -> "model.chat".equals(s.getName())
                        && traceId.equals(s.getTraceId())),
                () -> "应有同 trace 的 model.chat: " + names(spans));
        assertTrue(spans.stream().anyMatch(s -> "tool.invoke".equals(s.getName())
                        && traceId.equals(s.getTraceId())),
                () -> "应有同 trace 的 tool.invoke: " + names(spans));
    }

    @Test
    void outboxConsumerContinuesProducerTrace() {
        tracingConsumer.clear();
        exporter.reset();
        Tracer tracer = GlobalOpenTelemetry.getTracer("tioka-test");
        Span producer = tracer.spanBuilder("test.produce").startSpan();
        String marker = "trace-" + System.nanoTime();
        String producerTraceId = producer.getSpanContext().getTraceId();
        String producerSpanId = producer.getSpanContext().getSpanId();
        try (Scope ignored = producer.makeCurrent()) {
            tx.executeWithoutResult(status -> principals.runAs(testPrincipal(), () -> {
                publisher.publish(new DomainEvent(
                        "tioka.orch.run_created.v1",
                        "tioka-orch",
                        "run_" + marker,
                        Map.of("runId", "run_" + marker, "marker", marker),
                        0));
                return null;
            }));
        } finally {
            producer.end();
        }

        SysOutboxDO row = outboxMapper.selectOne(Wrappers.<SysOutboxDO>lambdaQuery()
                .like(SysOutboxDO::getPayloadJson, marker)
                .last("LIMIT 1"));
        assertNotNull(row, "事务提交后 sys_outbox 应有行");
        assertTrue(row.getPayloadJson().contains(producerTraceId),
                "信封 traceparent 应含生产者 traceId");

        delivery.deliver(tracingConsumer.resolvedConsumerName(), row.getTopic(), row.getTag(),
                row.getPayloadJson(), 0);
        assertEquals(1, tracingConsumer.envelopes().size());
        assertNotNull(tracingConsumer.envelopes().get(0).traceparent());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData consume = requireSpan(spans, "messaging.consume");
        assertEquals(producerTraceId, consume.getTraceId(), "消费者 span 须与生产者同 trace");
        assertEquals(producerSpanId, consume.getParentSpanId(), "消费者应以生产者 span 为父");
    }

    private static SpanData requireSpan(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 span " + name + ": " + names(spans)));
    }

    private static String names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList().toString();
    }

    private static Principal testPrincipal() {
        return new Principal("42", "ws_tracing", PrincipalType.USER,
                Set.of("workspace_member"), Set.of("*"), "password");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        TracingConsumer tracingConsumer() {
            return new TracingConsumer();
        }
    }

    static final class TracingConsumer extends IdempotentConsumer<Map<String, Object>> {
        private final List<EventEnvelope> envelopes = new CopyOnWriteArrayList<>();

        @Override
        protected String consumerName() {
            return "tracing-it-recorder";
        }

        @Override
        protected Class<Map<String, Object>> payloadType() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Map<String, Object>> type = (Class) Map.class;
            return type;
        }

        @Override
        protected String topic() {
            return "tioka-orch";
        }

        @Override
        protected String tagExpression() {
            return "tioka.orch.run_created.v1";
        }

        @Override
        protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
            envelopes.add(envelope);
        }

        List<EventEnvelope> envelopes() {
            return envelopes;
        }

        void clear() {
            envelopes.clear();
        }
    }
}
