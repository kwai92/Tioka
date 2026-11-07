package com.tioka.orchestration.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.OrchRuleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * V132：旧 claim/attention HTTP 废弃；events 转发 engine.OrchApi.ingest。
 */
@RestController
@RequestMapping("/api/orch")
@RequirePermission(Permissions.ORCH_WRITE)
public class OrchController {
    private final OrchApi orchEngine;
    private final OrchRuleService rules;

    public OrchController(OrchApi orchEngine, OrchRuleService rules) {
        this.orchEngine = orchEngine;
        this.rules = rules;
    }

    @PostMapping("/events")
    public ApiResponse<OrchApi.EventAck> ingest(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("eventType", body.get("type")));
        String idem = body.get("idempotencyKey") == null ? null : String.valueOf(body.get("idempotencyKey"));
        OrchApi.EventEnvelope env = new OrchApi.EventEnvelope(
                String.valueOf(body.getOrDefault("source", "http")),
                type,
                idem,
                body.get("subjectKey") == null ? null : String.valueOf(body.get("subjectKey")),
                Map.of(),
                body,
                0);
        return ApiResponse.ok(orchEngine.ingest(env));
    }

    @GetMapping("/rules")
    public ApiResponse<List<OrchRuleService.RuleView>> listRules() {
        return ApiResponse.ok(rules.list());
    }

    @PostMapping("/rules")
    public ApiResponse<OrchRuleService.RuleView> saveRule(@RequestBody Map<String, Object> body) {
        throw gone("规则写入待 ORC-05");
    }

    @PostMapping("/rules/{id}/publish")
    public ApiResponse<OrchRuleService.RuleView> publishRule(@PathVariable String id) {
        throw gone("规则发布待 ORC-05");
    }

    @PostMapping("/runs/claim")
    public ApiResponse<?> claim(@RequestBody Map<String, String> body) {
        throw gone("claim 已随 V132 移除");
    }

    @PostMapping("/runs/{id}/heartbeat")
    public ApiResponse<Void> heartbeat(@PathVariable String id, @RequestBody Map<String, String> body) {
        throw gone("heartbeat 已随 V132 移除");
    }

    @PostMapping("/attentions")
    public ApiResponse<?> open(@RequestBody Map<String, Object> body) {
        throw gone("orch_attention 已随 V132 移除；待 ORC-04");
    }

    @PostMapping("/attentions/{id}/answer")
    public ApiResponse<?> answer(@PathVariable String id, @RequestBody Map<String, Object> body) {
        throw gone("orch_attention 已随 V132 移除；待 ORC-04");
    }

    @GetMapping("/attentions")
    public ApiResponse<?> listOpen() {
        throw gone("orch_attention 已随 V132 移除；待 ORC-04");
    }

    @GetMapping("/runs/{id}/why")
    public ApiResponse<List<OrchApi.FactView>> why(@PathVariable String id) {
        return ApiResponse.ok(orchEngine.facts(id, 0));
    }

    private static BizException gone(String msg) {
        return new BizException(ErrorCode.NOT_IMPLEMENTED, msg);
    }
}
