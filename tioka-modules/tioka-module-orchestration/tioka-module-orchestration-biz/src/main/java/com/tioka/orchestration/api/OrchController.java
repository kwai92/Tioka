package com.tioka.orchestration.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.NotImplemented;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旧编排 REST 已删除（ORC-05 / V132）。规则面见 {@link OrchRuleController}；
 * 接入 / 派工 / 人审由 ORC-03、ORC-04 重建。
 */
@RestController
@RequestMapping("/api/orch")
@RequirePermission(Permissions.ORCH_WRITE)
public class OrchController {

    @PostMapping("/events")
    public ApiResponse<Void> ingestRemoved() {
        throw NotImplemented.of("OrchController.ingest", "ORC-03");
    }

    @PostMapping("/runs/claim")
    public ApiResponse<Void> claimRemoved() {
        throw NotImplemented.of("OrchController.claim", "ORC-03");
    }

    @PostMapping("/runs/{id}/heartbeat")
    public ApiResponse<Void> heartbeatRemoved(@PathVariable String id) {
        throw NotImplemented.of("OrchController.heartbeat", "ORC-03");
    }

    @PostMapping("/attentions")
    public ApiResponse<Void> openAttentionRemoved() {
        throw NotImplemented.of("OrchController.openAttention", "ORC-04");
    }

    @PostMapping("/attentions/{id}/answer")
    public ApiResponse<Void> answerRemoved(@PathVariable String id) {
        throw NotImplemented.of("OrchController.answer", "ORC-04");
    }

    @GetMapping("/attentions")
    public ApiResponse<Void> listAttentionsRemoved() {
        throw NotImplemented.of("OrchController.listAttentions", "ORC-04");
    }

    @GetMapping("/runs/{id}/why")
    public ApiResponse<Void> whyRemoved(@PathVariable String id) {
        throw NotImplemented.of("OrchController.why", "ORC-03");
    }
}
