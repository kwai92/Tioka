package com.tioka.orchestration.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.NotImplemented;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.system.api.audit.Audited;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 编排 REST：事件接入 / Run 查询（ORC-03）。人审面由 ORC-04 重建。
 */
@RestController
@RequestMapping("/api/orch")
public class OrchController {

    private final OrchApi orchApi;

    public OrchController(OrchApi orchApi) {
        this.orchApi = orchApi;
    }

    @PostMapping("/events")
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.event.ingest", resourceType = "orch_event")
    public ApiResponse<OrchApi.EventAck> ingest(@RequestBody Map<String, Object> body) {
        String source = str(body.get("source"));
        String type = str(body.get("type"));
        String idem = str(body.get("idempotencyKey"));
        String subject = str(body.get("subjectKey"));
        @SuppressWarnings("unchecked")
        Map<String, String> attrs = body.get("attrs") instanceof Map<?, ?> m
                ? castAttrs(m) : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> eventBody = body.get("body") instanceof Map<?, ?> m
                ? castBody(m) : Map.of();
        int hops = body.get("hops") instanceof Number n ? n.intValue() : 0;
        return ApiResponse.ok(orchApi.ingest(
                new OrchApi.EventEnvelope(source, type, idem, subject, attrs, eventBody, hops)));
    }

    @GetMapping("/runs/{id}")
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<OrchApi.RunView> getRun(@PathVariable String id) {
        return ApiResponse.ok(orchApi.getRun(id));
    }

    @GetMapping("/runs/{id}/facts")
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<List<OrchApi.FactView>> facts(@PathVariable String id,
                                                     @RequestParam(defaultValue = "0") long afterSeq) {
        return ApiResponse.ok(orchApi.facts(id, afterSeq));
    }

    @PostMapping("/runs/{id}/cancel")
    @RequirePermission(Permissions.ORCH_OPERATE)
    @Audited(action = "orch.run.cancel", resourceType = "orch_run", resourceIdArg = "#id")
    public ApiResponse<Void> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : str(body.get("reason"));
        orchApi.cancelRun(id, reason);
        return ApiResponse.ok(null);
    }

    @PostMapping("/runs/claim")
    @RequirePermission(Permissions.ORCH_WRITE)
    public ApiResponse<Void> claimRemoved() {
        throw NotImplemented.of("OrchController.claim", "ORC-03");
    }

    @PostMapping("/runs/{id}/heartbeat")
    @RequirePermission(Permissions.ORCH_WRITE)
    public ApiResponse<Void> heartbeatRemoved(@PathVariable String id) {
        throw NotImplemented.of("OrchController.heartbeat", "ORC-03");
    }

    @PostMapping("/attentions")
    @RequirePermission(Permissions.ORCH_WRITE)
    public ApiResponse<Void> openAttentionRemoved() {
        throw NotImplemented.of("OrchController.openAttention", "ORC-04");
    }

    @PostMapping("/attentions/{id}/answer")
    @RequirePermission(Permissions.ORCH_WRITE)
    public ApiResponse<Void> answerRemoved(@PathVariable String id) {
        throw NotImplemented.of("OrchController.answer", "ORC-04");
    }

    @GetMapping("/attentions")
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<Void> listAttentionsRemoved() {
        throw NotImplemented.of("OrchController.listAttentions", "ORC-04");
    }

    @GetMapping("/runs/{id}/why")
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<List<OrchApi.FactView>> why(@PathVariable String id,
                                                   @RequestParam(defaultValue = "0") long afterSeq) {
        return ApiResponse.ok(orchApi.facts(id, afterSeq));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, String> castAttrs(Map<?, ?> m) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    private static Map<String, Object> castBody(Map<?, ?> m) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }
}
