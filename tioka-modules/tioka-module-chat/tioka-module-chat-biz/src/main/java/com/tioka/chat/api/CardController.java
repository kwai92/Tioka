package com.tioka.chat.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.chat.application.CardPartService;
import com.tioka.chat.domain.ChatStore;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/cards")
@RequirePermission(Permissions.CHAT_USE)
public class CardController {
    private final CardPartService cards;

    public CardController(CardPartService cards) {
        this.cards = cards;
    }

    @PostMapping("/continue")
    @Audited(action = "chat.card.continue", resourceType = "card")
    public ApiResponse<ChatStore.CardMessage> continueCard(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(cards.emitContinueCard(body.get("conversationId"), body.get("prompt")));
    }

    @PostMapping("/approval")
    @Audited(action = "chat.card.approval", resourceType = "card")
    public ApiResponse<ChatStore.CardMessage> approval(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(cards.emitApprovalCard(body.get("conversationId"), body.get("runId"), body.get("title")));
    }

    @PostMapping("/continue/submit")
    @Audited(action = "chat.card.continue.submit", resourceType = "card")
    public ApiResponse<ChatStore.CardMessage> submitContinue(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(cards.submitContinue(
                body.get("conversationId"),
                body.get("messageIdOrPartHint"),
                body.get("answerText")));
    }

    @PostMapping("/approval/submit")
    @Audited(action = "chat.card.approval.submit", resourceType = "card")
    public ApiResponse<ChatStore.CardMessage> submitApproval(@RequestBody Map<String, Object> body) {
        String conversationId = body.get("conversationId") == null ? null : String.valueOf(body.get("conversationId"));
        String attentionId = body.get("attentionId") == null ? null : String.valueOf(body.get("attentionId"));
        boolean approved = Boolean.TRUE.equals(body.get("approved"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approved")));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return ApiResponse.ok(cards.submitApproval(conversationId, attentionId, approved, comment));
    }
}
