package com.tioka.chat.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.chat.application.ChatService;
import com.tioka.chat.domain.ChatStore;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.model.api.CostQueryApi;
import com.tioka.runtime.api.kernel.RuntimeApi;
import org.springframework.http.HttpStatus;
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
    @Audited(action = "chat.conversation.create", resourceType = "conversation")
    public ApiResponse<ChatService.CreatedConversation> create(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(chatService.create(body.get("title"), body.get("agentVersionId")));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<ChatStore.Conversation> get(@PathVariable String id) {
        return ApiResponse.ok(chatService.get(id));
    }

    @DeleteMapping("/conversations/{id}")
    @Audited(action = "chat.conversation.delete", resourceType = "conversation", resourceIdArg = "#id")
    public ApiResponse<Void> delete(@PathVariable String id) {
        chatService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Audited(action = "chat.message.send", resourceType = "conversation", resourceIdArg = "#id")
    public ApiResponse<ChatService.SendAccepted> send(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(chatService.send(id, str(body.get("text")), str(body.get("clientMsgId"))));
    }

    /**
     * 产品「消息列表」：返回绑定 Thread 上的 Runtime {@link RuntimeApi.ItemView} 投影
     *（非独立 chat_message；见 47-GL § Chat）。
     */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<RuntimeApi.ItemView>> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(chatService.messages(id, afterSeq, limit));
    }

    @PostMapping("/conversations/{id}/stop")
    public ApiResponse<Void> stop(@PathVariable String id) {
        chatService.stop(id);
        return ApiResponse.ok(null);
    }

    /** GOV-05：单会话成本（按关联 runtime thread 汇总）。 */
    @GetMapping("/conversations/{id}/cost")
    public ApiResponse<CostQueryApi.CostBucket> cost(@PathVariable String id) {
        return ApiResponse.ok(chatService.cost(id));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
