package com.tioka.chat.application;

import com.tioka.framework.id.Ids;

import com.tioka.chat.domain.ChatStore;
import com.tioka.chat.domain.ChatStore.Message;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.orchestration.api.OrchApi;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CardPartService {
    public static final String COPY_CONTINUE = "请选择后续";
    public static final String CTA_CONTINUE = "提交续答";
    public static final String COPY_APPROVAL = "需审批后继续";
    public static final String CTA_APPROVAL = "去审批";

    private final OrchApi orchApi;
    private final ChatStore store;

    public CardPartService(OrchApi orchApi, ChatStore store) {
        this.orchApi = orchApi;
        this.store = store;
    }

    public Message emitContinueCard(String conversationId, String prompt) {
        ensureConv(conversationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "continue");
        payload.put("prompt", prompt);
        payload.put("visual", "inline-continue");
        payload.put("copy", COPY_CONTINUE);
        payload.put("cta", CTA_CONTINUE);
        // CHT-03：卡片内容不再写入 chat_message；响应 DTO 仅供调用方展示。
        return new Message(newMsgId(), conversationId, "assistant",
                "[interactive_card/continue] " + prompt,
                List.of(part("interactive_card", prompt, payload)));
    }

    public Message emitApprovalCard(String conversationId, String runId, String title) {
        ensureConv(conversationId);
        var attention = orchApi.openAttention(runId, title, Map.of("mode", "suspend"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "suspend");
        payload.put("attentionId", attention.id());
        payload.put("visual", "approval-suspend");
        payload.put("copy", COPY_APPROVAL);
        payload.put("cta", CTA_APPROVAL);
        return new Message(newMsgId(), conversationId, "assistant",
                "[approval_card/suspend] " + title,
                List.of(part("approval_card", title, payload)));
    }

    /**
     * 对话内续答：写入 user 消息，不挂起 Run。
     */
    public Message submitContinue(String conversationId, String messageIdOrPartHint, String answerText) {
        ensureConv(conversationId);
        if (answerText == null || answerText.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "续答内容不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "continue_submit");
        payload.put("mode", "continue");
        if (messageIdOrPartHint != null && !messageIdOrPartHint.isBlank()) {
            payload.put("ref", messageIdOrPartHint);
        }
        return new Message(newMsgId(), conversationId, "user", answerText,
                List.of(part("text", answerText, payload)));
    }

    /**
     * 审批提交：调用 Orch.answer，并写入结果气泡。
     */
    public Message submitApproval(String conversationId, String attentionId, boolean approved, String comment) {
        ensureConv(conversationId);
        if (attentionId == null || attentionId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "attentionId 不能为空");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("approved", approved);
        if (comment != null && !comment.isBlank()) {
            answer.put("comment", comment);
        }
        orchApi.answer(attentionId, answer);

        String text = approved ? "已批准续跑" : "已拒绝";
        if (comment != null && !comment.isBlank()) {
            text = text + "：" + comment;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "approval_result");
        payload.put("attentionId", attentionId);
        payload.put("approved", approved);
        payload.put("copy", text);
        return new Message(newMsgId(), conversationId, "assistant", text,
                List.of(part("text", text, payload)));
    }

    private static Map<String, Object> part(String type, String text, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("text", text == null ? "" : text);
        p.put("payload", payload);
        return p;
    }

    private static String newMsgId() {
        return Ids.newId("msg");
    }

    private void ensureConv(String conversationId) {
        if (!store.hasConversation(conversationId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
    }
}
