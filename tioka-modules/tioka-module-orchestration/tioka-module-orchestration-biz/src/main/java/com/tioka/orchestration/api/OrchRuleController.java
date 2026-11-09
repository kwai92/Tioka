package com.tioka.orchestration.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.orchestration.application.OrchRuleAppService;
import com.tioka.orchestration.application.OrchRuleAppService.CreateVersionCommand;
import com.tioka.orchestration.application.OrchRuleAppService.DryRunResult;
import com.tioka.orchestration.application.OrchRuleAppService.PublishCommand;
import com.tioka.orchestration.application.OrchRuleAppService.RuleView;
import com.tioka.orchestration.application.OrchRuleAppService.VersionView;
import com.tioka.system.api.audit.Audited;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orch/rules")
public class OrchRuleController {
    private final OrchRuleAppService rules;

    public OrchRuleController(OrchRuleAppService rules) {
        this.rules = rules;
    }

    @GetMapping
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<List<RuleView>> list() {
        return ApiResponse.ok(rules.listRules());
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.ORCH_READ)
    public ApiResponse<RuleView> get(@PathVariable String id) {
        return ApiResponse.ok(rules.getRule(id));
    }

    @PostMapping
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.rule.create", resourceType = "orch_rule")
    public ApiResponse<RuleView> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(rules.createRule(
                body.get("name") == null ? null : String.valueOf(body.get("name"))));
    }

    @PostMapping("/{id}/versions")
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.rule.version.create", resourceType = "orch_rule", resourceIdArg = "#id")
    public ApiResponse<VersionView> createVersion(@PathVariable String id,
                                                  @RequestBody CreateVersionCommand body) {
        return ApiResponse.ok(rules.createVersion(id, body));
    }

    @PostMapping("/{id}/versions/{vid}/publish")
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.rule.publish", resourceType = "orch_rule_version", resourceIdArg = "#vid")
    public ApiResponse<VersionView> publish(@PathVariable String id,
                                            @PathVariable String vid,
                                            @RequestBody PublishCommand body) {
        return ApiResponse.ok(rules.publish(id, vid, body == null
                ? new PublishCommand("STABLE", null, null) : body));
    }

    @PostMapping("/{id}/versions/{vid}/dry-run")
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.rule.dry_run", resourceType = "orch_rule_version", resourceIdArg = "#vid")
    public ApiResponse<DryRunResult> dryRun(@PathVariable String id,
                                            @PathVariable String vid,
                                            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> event = body.get("event") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : body;
        return ApiResponse.ok(rules.dryRun(id, vid, event));
    }

    /** 显式恢复（ORC-07 / FE-17）：清自动暂停标记，规则重新参与派发。 */
    @PostMapping("/{id}/resume")
    @RequirePermission(Permissions.ORCH_WRITE)
    @Audited(action = "orch.rule.resume", resourceType = "orch_rule", resourceIdArg = "#id")
    public ApiResponse<Void> resume(@PathVariable String id) {
        rules.resumeRule(id);
        return ApiResponse.ok(null);
    }
}
