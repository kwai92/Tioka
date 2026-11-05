
package com.tioka.chat.api;

import com.tioka.chat.application.ChatService;
import com.tioka.chat.domain.ChatStore;
import com.tioka.framework.error.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
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

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<ChatStore.Message> send(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(chatService.send(id, body.get("text"), body.get("clientMsgId")));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<ChatStore.Message>> messages(@PathVariable String id) {
        return ApiResponse.ok(chatService.messages(id));
    }
}
