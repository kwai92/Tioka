package com.tioka.connector.api;

import com.tioka.connector.infrastructure.FeishuImChannelAdapter;
import com.tioka.framework.error.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connector/im")
public class ImController {
    private final FeishuImChannelAdapter feishu;

    public ImController(FeishuImChannelAdapter feishu) {
        this.feishu = feishu;
    }

    @PostMapping("/feishu/text")
    public ApiResponse<Void> sendText(@RequestBody Map<String, String> body) {
        feishu.sendText(body.get("conversationId"), body.get("text"));
        return ApiResponse.ok(null);
    }

    @GetMapping("/feishu/outbox")
    public ApiResponse<List<Map<String, Object>>> outbox() {
        return ApiResponse.ok(feishu.outbox());
    }
}
