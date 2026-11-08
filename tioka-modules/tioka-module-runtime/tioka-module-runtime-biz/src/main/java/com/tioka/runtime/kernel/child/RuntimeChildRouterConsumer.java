package com.tioka.runtime.kernel.child;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费 {@code tioka.runtime.child_finished.v1}，写 tool_result 并续跑（RT-09）。
 */
@Component
public class RuntimeChildRouterConsumer extends IdempotentConsumer<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(RuntimeChildRouterConsumer.class);

    private final ChildResumeService resume;

    public RuntimeChildRouterConsumer(ChildResumeService resume) {
        this.resume = resume;
    }

    @Override
    protected String consumerName() {
        return "runtime-child-router";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return RuntimeMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return RuntimeMqTopics.CHILD_FINISHED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String turnId = stringOf(payload.get("turnId"));
        String callId = stringOf(payload.get("callId"));
        boolean ok = Boolean.TRUE.equals(payload.get("ok"))
                || "true".equalsIgnoreCase(stringOf(payload.get("ok")));
        Object output = payload.get("output");
        String errorCode = stringOf(payload.get("errorCode"));
        boolean done = resume.resume(turnId, callId, ok, output, errorCode);
        if (!done) {
            log.debug("child_finished 未续跑 turnId={} callId={} eventId={}",
                    turnId, callId, envelope.eventId());
        }
    }

    /** IT/local：同进程即时路由（handler 幂等）。 */
    public void routeLocally(String turnId, String callId, boolean ok, Object output, String errorCode) {
        resume.resume(turnId, callId, ok, output, errorCode);
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
