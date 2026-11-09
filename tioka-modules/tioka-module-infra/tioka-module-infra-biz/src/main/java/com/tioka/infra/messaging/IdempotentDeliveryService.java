package com.tioka.infra.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.infra.messaging.infrastructure.SysConsumedRepository;
import com.tioka.infra.messaging.infrastructure.SysDeadLetterRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RocketMQ / in-memory 共用的幂等投递与死信写入（ORC-02）。
 */
@Service
public class IdempotentDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentDeliveryService.class);

    /**
     * P2-L6：消费线程 MDC 回写 key。与 system-biz {@code AuditRequestContext}
     * （MDC_TRACE/MDC_SPAN，值为 "traceId"/"spanId"）保持同源；infra-biz 不依赖
     * system-biz，故以字面常量镜像（离线契约测试 P2L6TraceContinuityContractTest 守护）。
     */
    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_SPAN_ID = "spanId";

    private final List<IdempotentConsumer<?>> consumers;
    private final SysConsumedRepository consumed;
    private final SysDeadLetterRepository deadLetters;
    private final PrincipalHolder principals;
    private final ObjectProvider<PrincipalLoaderApi> principalLoader;
    private final ObjectMapper mapper;
    private final MessagingProperties props;
    private final TransactionTemplate requiredTx;
    private final TransactionTemplate requiresNewTx;

    public IdempotentDeliveryService(List<IdempotentConsumer<?>> consumers,
                                     SysConsumedRepository consumed,
                                     SysDeadLetterRepository deadLetters,
                                     PrincipalHolder principals,
                                     ObjectProvider<PrincipalLoaderApi> principalLoader,
                                     ObjectMapper mapper,
                                     MessagingProperties props,
                                     PlatformTransactionManager txManager) {
        this.consumers = consumers;
        this.consumed = consumed;
        this.deadLetters = deadLetters;
        this.principals = principals;
        this.principalLoader = principalLoader;
        this.mapper = mapper;
        this.props = props;
        this.requiredTx = new TransactionTemplate(txManager);
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 按消费组投递一次（IT / RocketMQ 适配器按 reconsumeTimes 调用）。
     * {@code reconsumeTimes} 从 0 起；最后一次失败写死信并确认。
     */
    public void deliver(String consumerName, String topic, String tag, String payloadJson, int reconsumeTimes) {
        IdempotentConsumer<?> consumer = findConsumer(consumerName)
                .orElseThrow(() -> new IllegalArgumentException("未知消费者: " + consumerName));
        deliverOnce(consumer, topic, tag, payloadJson, reconsumeTimes);
    }

    /** in-memory：本进程内重试至 max-attempts，末次失败写死信。 */
    public void deliverAndRetry(IdempotentConsumer<?> consumer, String topic, String tag, String payloadJson) {
        int max = props.getConsumer().getMaxAttempts();
        for (int attempt = 0; attempt < max; attempt++) {
            try {
                deliverOnce(consumer, topic, tag, payloadJson, attempt);
                return;
            } catch (RuntimeException e) {
                log.warn("消费失败 consumer={} attempt={}/{}: {}",
                        consumer.resolvedConsumerName(), attempt + 1, max, e.toString());
                if (attempt >= max - 1) {
                    // deliverOnce 已在末次写入死信；此处不再抛出，避免中继将整批标失败
                    return;
                }
            }
        }
    }

    private void deliverOnce(IdempotentConsumer<?> consumer, String topic, String tag,
                             String payloadJson, int reconsumeTimes) {
        int max = props.getConsumer().getMaxAttempts();
        try {
            requiredTx.executeWithoutResult(status -> process(consumer, payloadJson));
        } catch (RuntimeException e) {
            if (reconsumeTimes >= max - 1) {
                writeDeadLetter(consumer.resolvedConsumerName(), topic, tag, payloadJson, e, reconsumeTimes + 1);
                return;
            }
            throw e;
        }
    }

    private void process(IdempotentConsumer<?> consumer, String payloadJson) {
        EventEnvelope envelope = readEnvelope(payloadJson);
        boolean first = consumed.tryMarkConsumed(consumer.resolvedConsumerName(), envelope.eventId());
        if (!first) {
            return;
        }
        Context parent = extractParent(envelope.traceparent());
        Span span = GlobalOpenTelemetry.getTracer("tioka")
                .spanBuilder("messaging.consume")
                .setParent(parent)
                .setAttribute("tioka.messaging.consumer", consumer.resolvedConsumerName())
                .setAttribute("tioka.messaging.event", envelope.event() == null ? "" : envelope.event())
                .startSpan();
        // P2-L6：派发 handler 前回写 MDC，使消费侧审计与消息链路同 traceId；
        // Span 上下文无效（未挂 agent）时回退 envelope.traceId，均无效则不写（审计自生成 trc_）
        boolean mdcWritten = propagateTraceToMdc(span.getSpanContext(), envelope.traceId());
        try (Scope ignored = span.makeCurrent()) {
            Object payload = readPayload(consumer, envelope);
            Principal runAs = resolveRunAs(envelope);
            RequestContext previous = RequestContextHolder.getCorrelation();
            try {
                if (envelope.requestId() != null || envelope.subject() != null) {
                    RequestContextHolder.setCorrelation(
                            new RequestContext(envelope.requestId(), envelope.subject(), null));
                }
                principals.runAs(runAs, () -> {
                    consumer.dispatch(envelope, payload);
                    return null;
                });
            } finally {
                RequestContextHolder.setCorrelation(previous);
            }
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            if (mdcWritten) {
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_SPAN_ID);
            }
            span.end();
        }
    }

    /**
     * P2-L6：优先以 Span 上下文（extractParent 得到的 W3C trace-id + span-id）写 MDC；
     * Span 无效且 envelope.traceId 非空时仅写 traceId。返回是否写入了 MDC（调用方 finally 清理）。
     */
    private static boolean propagateTraceToMdc(io.opentelemetry.api.trace.SpanContext spanContext, String envelopeTraceId) {
        if (spanContext != null && spanContext.isValid()) {
            MDC.put(MDC_TRACE_ID, spanContext.getTraceId());
            MDC.put(MDC_SPAN_ID, spanContext.getSpanId());
            return true;
        }
        if (envelopeTraceId != null && !envelopeTraceId.isBlank()) {
            MDC.put(MDC_TRACE_ID, envelopeTraceId);
            return true;
        }
        return false;
    }

    private static Context extractParent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.current();
        }
        return W3CTraceContextPropagator.getInstance().extract(
                Context.current(),
                Map.of("traceparent", traceparent),
                TRACEPARENT_GETTER);
    }

    private static final TextMapGetter<Map<String, String>> TRACEPARENT_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier == null ? Collections.emptySet() : carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    private EventEnvelope readEnvelope(String payloadJson) {
        try {
            return mapper.readValue(payloadJson, EventEnvelope.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法反序列化信封", e);
        }
    }

    private Object readPayload(IdempotentConsumer<?> consumer, EventEnvelope envelope) {
        Class<?> type = consumer.resolvedPayloadType();
        Object raw = envelope.payload();
        if (raw == null) {
            return null;
        }
        if (type.isInstance(raw)) {
            return raw;
        }
        try {
            if (Map.class.isAssignableFrom(type) && raw instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }
            if (raw instanceof JsonNode || raw instanceof Map) {
                return mapper.convertValue(raw, type);
            }
            return mapper.convertValue(raw, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法反序列化 payload 为 " + type.getName(), e);
        }
    }

    private Principal resolveRunAs(EventEnvelope envelope) {
        EventEnvelope.RunAs runAs = envelope.runAs();
        if (runAs == null || runAs.userId() == null || runAs.userId().isBlank()) {
            return SystemPrincipals.system();
        }
        if ("SYSTEM".equalsIgnoreCase(runAs.type())) {
            return SystemPrincipals.system();
        }
        PrincipalLoaderApi loader = principalLoader.getIfAvailable();
        if (loader != null) {
            try {
                long uid = Long.parseLong(runAs.userId());
                return loader.load(uid, runAs.workspaceId());
            } catch (Exception e) {
                log.warn("runAs 重载失败，退回身份片段: {}", e.toString());
            }
        }
        PrincipalType type = parseType(runAs.type());
        return new Principal(runAs.userId(), runAs.workspaceId(), type, Set.of(), Set.of(), "mq");
    }

    private static PrincipalType parseType(String type) {
        if (type == null || type.isBlank()) {
            return PrincipalType.USER;
        }
        try {
            return PrincipalType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            return PrincipalType.USER;
        }
    }

    private void writeDeadLetter(String consumerName, String topic, String tag,
                                 String payloadJson, Exception error, int attempts) {
        requiresNewTx.executeWithoutResult(status -> {
            EventEnvelope env = null;
            try {
                env = readEnvelope(payloadJson);
            } catch (Exception ignored) {
                // keep null
            }
            SysDeadLetterDO row = new SysDeadLetterDO();
            row.setId(stripPrefix(Ids.newId("dl"), "dl_"));
            row.setConsumer(consumerName);
            row.setTopic(topic);
            row.setTag(tag);
            row.setEventId(env == null ? "unknown" : env.eventId());
            row.setPayloadJson(payloadJson);
            row.setError(error == null ? null : truncate(error.toString(), 2000));
            row.setAttempts(attempts);
            row.setStatus("OPEN");
            row.setCreateAt(Instant.now());
            deadLetters.insert(row);
        });
    }

    private Optional<IdempotentConsumer<?>> findConsumer(String name) {
        return consumers.stream()
                .filter(c -> c.resolvedConsumerName().equals(name))
                .findFirst();
    }

    private static String stripPrefix(String id, String prefix) {
        return id != null && id.startsWith(prefix) ? id.substring(prefix.length()) : id;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
