package com.tioka.chat.api;

import com.tioka.chat.application.ChatService;
import com.tioka.chat.domain.ChatStore;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.model.api.CostQueryApi;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequirePermission(Permissions.CHAT_USE)
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ChatStore.Conversation>> list() {
        return ApiResponse.ok(chatService.list());
    }

    @PostMapping("/conversations")
    public ApiResponse<ChatStore.Conversation> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(chatService.create(body.get("title"), body.get("agentVersionId")));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<ChatStore.Conversation> get(@PathVariable String id) {
        return ApiResponse.ok(chatService.get(id));
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        chatService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<ChatStore.Message> send(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(chatService.send(id, body.get("text"), body.get("clientMsgId")));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<ChatStore.Message>> messages(@PathVariable String id) {
        return ApiResponse.ok(chatService.messages(id));
    }

    /** GOV-05：单会话成本（按关联 runtime thread / session 汇总）。 */
    @GetMapping("/conversations/{id}/cost")
    public ApiResponse<CostQueryApi.CostBucket> cost(@PathVariable String id) {
        return ApiResponse.ok(chatService.cost(id));
    }
}
