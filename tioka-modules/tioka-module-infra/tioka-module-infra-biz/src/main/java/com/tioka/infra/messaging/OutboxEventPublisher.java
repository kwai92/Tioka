package com.tioka.infra.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.infra.messaging.infrastructure.SysOutboxRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * 事务内登记 {@code sys_outbox}（ORC-02）。必须在已有事务中调用（{@link Propagation#MANDATORY}）。
 */
@Component
public class OutboxEventPublisher implements EventPublisher {

    private final SysOutboxRepository outbox;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final EnvelopeSchemaValidator validator;

    public OutboxEventPublisher(SysOutboxRepository outbox,
                                PrincipalHolder principals,
                                ObjectMapper mapper,
                                EnvelopeSchemaValidator validator) {
        this.outbox = outbox;
        this.principals = principals;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        String eventId = stripEvtPrefix(Ids.newId("evt"));
        Principal principal = principals.find().orElse(null);
        RequestContext correlation = RequestContextHolder.getCorrelation();

        String requestId = firstNonBlank(event.requestId(), correlation.requestId());
        String subject = firstNonBlank(event.subject(), correlation.subject());

        SpanContext spanCtx = Span.current().getSpanContext();
        String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;
        String traceparent = spanCtx.isValid()
                ? "00-" + spanCtx.getTraceId() + "-" + spanCtx.getSpanId() + "-"
                + (spanCtx.getTraceFlags().isSampled() ? "01" : "00")
                : null;

        EventEnvelope.RunAs runAs = null;
        String workspaceId = null;
        if (principal != null) {
            workspaceId = principal.workspaceId();
            runAs = new EventEnvelope.RunAs(
                    principal.userId(),
                    principal.workspaceId(),
                    principal.type().name());
        }

        EventEnvelope envelope = new EventEnvelope(
                eventId,
                event.event(),
                1,
                workspaceId,
                Instant.now().toString(),
                resolveProducer(),
                traceId,
                traceparent,
                null,
                requestId,
                subject,
                event.subjectKey(),
                event.hops() == null ? 0 : event.hops(),
                runAs,
                event.payload());

        ObjectMapper nonNull = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        JsonNode tree = nonNull.valueToTree(envelope);
        validator.validate(tree);

        String payloadJson;
        try {
            payloadJson = nonNull.writeValueAsString(tree);
        } catch (Exception e) {
            throw new IllegalStateException("序列化信封失败", e);
        }

        SysOutboxDO row = new SysOutboxDO();
        row.setEventId(eventId);
        row.setTopic(event.topic());
        row.setTag(event.event());
        row.setMsgKey(event.subjectKey());
        row.setPayloadJson(payloadJson);
        row.setAttempts(0);
        row.setCreateAt(Instant.now());
        outbox.insert(row);
        outbox.notifyChannel();
    }

    private static String stripEvtPrefix(String id) {
        if (id != null && id.startsWith("evt_")) {
            return id.substring(4);
        }
        return id;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    /** 取调用方模块简名，如 {@code orchestration-biz}。 */
    private static String resolveProducer() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stream ->
                stream.map(StackWalker.StackFrame::getDeclaringClass)
                        .map(Class::getName)
                        .filter(n -> n.startsWith("com.tioka."))
                        .filter(n -> !n.startsWith("com.tioka.infra.messaging"))
                        .filter(n -> !n.startsWith("com.tioka.framework.messaging"))
                        .filter(n -> !n.contains(".springframework."))
                        .findFirst()
                        .map(OutboxEventPublisher::moduleNameOf)
                        .orElse("tioka"));
    }

    private static String moduleNameOf(String className) {
        // com.tioka.orchestration.application.Xxx → orchestration-biz
        String[] parts = className.split("\\.");
        if (parts.length >= 3 && "com".equals(parts[0]) && "tioka".equals(parts[1])) {
            return parts[2].toLowerCase(Locale.ROOT) + "-biz";
        }
        return className;
    }
}
