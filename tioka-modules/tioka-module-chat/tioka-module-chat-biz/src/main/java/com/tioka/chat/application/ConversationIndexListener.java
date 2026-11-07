package com.tioka.chat.application;

import com.tioka.chat.domain.ChatStore;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.event.TurnCompletedEvent;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnAwaiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * CHT-03：Turn 完成且 origin=chat 时回写会话 {@code last_preview}。
 */
@Component
public class ConversationIndexListener {
    private static final Logger log = LoggerFactory.getLogger(ConversationIndexListener.class);
    private static final int PREVIEW_MAX = 200;

    private final ChatStore store;
    private final RuntimeApi runtime;
    private final ObjectProvider<TurnAwaiter> awaiters;
    private final PrincipalHolder principals;

    public ConversationIndexListener(ChatStore store,
                                     RuntimeApi runtime,
                                     ObjectProvider<TurnAwaiter> awaiters,
                                     PrincipalHolder principals) {
        this.store = store;
        this.runtime = runtime;
        this.awaiters = awaiters;
        this.principals = principals;
    }

    @EventListener
    public void onTurnCompleted(TurnCompletedEvent event) {
        if (event == null || !"chat".equals(event.origin())) {
            return;
        }
        // 派发线程无请求 Principal；以 SYSTEM 读 item（userId=0 跳过归属校验）
        principals.runAs(SystemPrincipals.system(), () -> {
            String conversationId = resolveConversationId(event);
            if (conversationId == null || conversationId.isBlank()) {
                log.debug("TurnCompletedEvent 无 conversationId，跳过 preview 更新 turnId={}", event.turnId());
                return;
            }
            String preview = previewText(event);
            if (preview == null || preview.isBlank()) {
                return;
            }
            store.updateLastPreview(conversationId, truncate(preview, PREVIEW_MAX));
        });
    }

    private String resolveConversationId(TurnCompletedEvent event) {
        if (event.originRef() != null && !event.originRef().isBlank()) {
            return event.originRef();
        }
        return store.findConversationIdByThreadId(event.threadId());
    }

    private String previewText(TurnCompletedEvent event) {
        TurnAwaiter awaiter = awaiters.getIfAvailable();
        if (awaiter != null) {
            return awaiter.finalText(event.turnId()).orElse(null);
        }
        String last = null;
        for (RuntimeApi.ItemView item : runtime.listItems(event.threadId(), 0, 500)) {
            if (!event.turnId().equals(item.turnId())) {
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
}
