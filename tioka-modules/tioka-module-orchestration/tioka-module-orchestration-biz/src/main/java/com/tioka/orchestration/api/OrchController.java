package com.tioka.orchestration.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.orchestration.application.OrchRuleService;
import com.tioka.orchestration.application.OrchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/orch")
@RequirePermission(Permissions.ORCH_WRITE)
public class OrchController {
    private final OrchService orch;
    private final OrchRuleService rules;

    public OrchController(OrchService orch, OrchRuleService rules) {
        this.orch = orch;
        this.rules = rules;
    }

    @PostMapping("/events")
    @Audited(action = "orch.event.ingest", resourceType = "orch_event")
    public ApiResponse<String> ingest(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(orch.ingestEvent(
                String.valueOf(body.get("eventType")),
                body.get("idempotencyKey") == null ? null : String.valueOf(body.get("idempotencyKey")),
                body));
    }

    @GetMapping("/rules")
    public ApiResponse<List<OrchRuleService.RuleView>> listRules() {
        return ApiResponse.ok(rules.list());
    }

    @PostMapping("/rules")
    @Audited(action = "orch.rule.save", resourceType = "orch_rule")
    public ApiResponse<OrchRuleService.RuleView> saveRule(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> action = body.get("action") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of("type", "create_run");
        return ApiResponse.ok(rules.saveDraft(
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                String.valueOf(body.get("eventType")),
                action));
    }

    @PostMapping("/rules/{id}/publish")
    @Audited(action = "orch.rule.publish", resourceType = "orch_rule", resourceIdArg = "#id")
    public ApiResponse<OrchRuleService.RuleView> publishRule(@PathVariable String id) {
        return ApiResponse.ok(rules.publish(id));
    }

    @PostMapping("/runs/claim")
    @Audited(action = "orch.run.claim", resourceType = "orch_run")
    public ApiResponse<OrchApi.RunView> claim(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(orch.claim(body.get("workerId")));
    }

    @PostMapping("/runs/{id}/heartbeat")
    @Audited(action = "orch.run.heartbeat", resourceType = "orch_run", resourceIdArg = "#id")
    public ApiResponse<Void> heartbeat(@PathVariable String id, @RequestBody Map<String, String> body) {
        orch.heartbeat(id, body.get("workerId"));
        return ApiResponse.ok(null);
    }

    @PostMapping("/attentions")
    @Audited(action = "orch.attention.open", resourceType = "orch_attention")
    public ApiResponse<OrchApi.AttentionView> open(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) body.getOrDefault("card", Map.of());
        return ApiResponse.ok(orch.openAttention(String.valueOf(body.get("runId")), String.valueOf(body.get("title")), card));
    }

    @PostMapping("/attentions/{id}/answer")
    @Audited(action = "orch.attention.answer", resourceType = "orch_attention", resourceIdArg = "#id")
    public ApiResponse<OrchApi.AttentionView> answer(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(orch.answer(id, body));
    }

    @GetMapping("/attentions")
    public ApiResponse<List<OrchApi.AttentionView>> listOpen() {
        return ApiResponse.ok(orch.openAttentions());
    }

    @GetMapping("/runs/{id}/why")
    public ApiResponse<List<OrchApi.WhyStep>> why(@PathVariable String id) {
        return ApiResponse.ok(orch.why(id));
    }
}
