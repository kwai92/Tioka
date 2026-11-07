package com.tioka.chat.application;

import com.tioka.chat.domain.ChatStore;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.event.TurnCompletedEvent;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnAwaiter;
import com.tioka.runtime.api.mq.RuntimeMqTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CHT-03 / ORC-02：消费 {@code tioka.runtime.turn_finished.v1} 回写会话 {@code last_preview}。
 *
 * <p>TurnRunner（K1）仍发 {@link TurnCompletedEvent}：本类监听后发布 turn_finished，并在同进程
 * 直接更新索引（IT/local 默认 {@code relay-enabled=false}）。MQ 路径与本地路径共用 {@link #apply}。
 */
@Component
public class ChatIndexConsumer extends IdempotentConsumer<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(ChatIndexConsumer.class);
    private static final int PREVIEW_MAX = 200;

    private final ChatStore store;
    private final RuntimeApi runtime;
    private final ObjectProvider<TurnAwaiter> awaiters;
    private final PrincipalHolder principals;
    private final EventPublisher events;
    private final TransactionTemplate tx;

    public ChatIndexConsumer(ChatStore store,
                             RuntimeApi runtime,
                             ObjectProvider<TurnAwaiter> awaiters,
                             PrincipalHolder principals,
                             EventPublisher events,
                             TransactionTemplate tx) {
        this.store = store;
        this.runtime = runtime;
        this.awaiters = awaiters;
        this.principals = principals;
        this.events = events;
        this.tx = tx;
    }

    @Override
    protected String consumerName() {
        return "chat-index";
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
        return RuntimeMqTopics.TURN_FINISHED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        apply(stringOf(payload.get("turnId")),
                stringOf(payload.get("threadId")),
                stringOf(payload.get("origin")),
                stringOf(payload.get("originRef")));
    }

    /**
     * K1 尚未改为直接发 turn_finished：桥接 Spring 事件 → outbox，并同进程更新索引。
     */
    @EventListener
    public void onTurnCompleted(TurnCompletedEvent event) {
        if (event == null || !"chat".equals(event.origin())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("turnId", nullToEmpty(event.turnId()));
        payload.put("threadId", nullToEmpty(event.threadId()));
        payload.put("status", "COMPLETED");
        payload.put("origin", nullToEmpty(event.origin()));
        payload.put("originRef", nullToEmpty(event.originRef()));
        try {
            tx.executeWithoutResult(status -> events.publish(new DomainEvent(
                    RuntimeMqTopics.TURN_FINISHED,
                    RuntimeMqTopics.TOPIC,
                    event.turnId(),
                    payload,
                    0,
                    null,
                    event.turnId() == null ? null : "turn:" + event.turnId())));
        } catch (RuntimeException e) {
            log.warn("发布 turn_finished 失败 turnId={}: {}", event.turnId(), e.toString());
        }
        apply(event.turnId(), event.threadId(), event.origin(), event.originRef());
    }

    private void apply(String turnId, String threadId, String origin, String originRef) {
        if (!"chat".equals(origin)) {
            return;
        }
        principals.runAs(SystemPrincipals.system(), () -> {
            String conversationId = resolveConversationId(originRef, threadId);
            if (conversationId == null || conversationId.isBlank()) {
                log.debug("turn_finished 无 conversationId，跳过 preview 更新 turnId={}", turnId);
                return;
            }
            String preview = previewText(turnId, threadId);
            if (preview == null || preview.isBlank()) {
                return;
            }
            store.updateLastPreview(conversationId, truncate(preview, PREVIEW_MAX));
        });
    }

    private String resolveConversationId(String originRef, String threadId) {
        if (originRef != null && !originRef.isBlank()) {
            return originRef;
        }
        return store.findConversationIdByThreadId(threadId);
    }

    private String previewText(String turnId, String threadId) {
        TurnAwaiter awaiter = awaiters.getIfAvailable();
        if (awaiter != null) {
            return awaiter.finalText(turnId).orElse(null);
        }
        String last = null;
        for (RuntimeApi.ItemView item : runtime.listItems(threadId, 0, 500)) {
            if (!turnId.equals(item.turnId())) {
                continue;
            }
            if (item.type() != ItemType.MESSAGE || !"assistant".equals(item.role())) {
                continue;
            }
            Object text = item.content() == null ? null : item.content().get("text");
            if (text != null) {
                last = String.valueOf(text);
            }
        }
        return last;
    }

    private static String truncate(String text, int max) {
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
