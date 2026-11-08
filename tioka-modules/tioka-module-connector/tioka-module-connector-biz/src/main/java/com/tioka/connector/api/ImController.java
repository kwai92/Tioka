package com.tioka.connector.api;

import com.tioka.connector.application.ImSendConsumer;
import com.tioka.connector.application.ImSendPublisher;
import com.tioka.connector.infrastructure.FeishuImChannelAdapter;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connector/im")
@RequirePermission(Permissions.CHAT_USE)
public class ImController {
    private final FeishuImChannelAdapter feishu;
    private final ImSendPublisher publisher;
    private final ImSendConsumer consumer;
    private final boolean relayEnabled;

    public ImController(FeishuImChannelAdapter feishu,
                        ImSendPublisher publisher,
                        ImSendConsumer consumer,
                        @Value("${tioka.messaging.relay-enabled:false}") boolean relayEnabled) {
        this.feishu = feishu;
        this.publisher = publisher;
        this.consumer = consumer;
        this.relayEnabled = relayEnabled;
    }

    @PostMapping("/feishu/text")
    public ApiResponse<Void> sendText(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        String text = body.get("text");
        String bindingId = body.getOrDefault("bindingId", "default");
        publisher.requestText("feishu", bindingId, conversationId, text);
        // 关 Relay 时同进程即时投递；开 Relay 时仅由 im-send 消费者执行，避免重复外发
        if (!relayEnabled) {
            consumer.sendLocally("feishu", conversationId, text);
        }
        return ApiResponse.ok(null);
    }

    @GetMapping("/feishu/outbox")
    public ApiResponse<List<Map<String, Object>>> outbox() {
        return ApiResponse.ok(feishu.outbox());
    }
}
