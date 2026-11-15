package com.tioka.governance.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.governance.api.alert.AlertEventView;
import com.tioka.governance.api.alert.AlertRuleCommand;
import com.tioka.governance.api.alert.AlertRuleView;
import com.tioka.governance.api.alert.AlertState;
import com.tioka.governance.application.alert.AlertRuleService;
import com.tioka.system.api.audit.Audited;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GOV-13 告警规则与事件（ADR-041 §5.3）。
 *
 * <p>读需 {@code ops:monitor}，写需 {@code ops:alert:admin}；写动作登记审计（改前改后另写
 * {@code cfg_change} 流水）。类级条件与 {@code AlertAutoConfiguration} 一致：随
 * {@code tioka.alerts.enabled}（默认开启）联动装配。
 */
@RestController
@RequestMapping("/api/governance/alerts")
@ConditionalOnProperty(prefix = "tioka.alerts", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AlertController {

    private final AlertRuleService alerts;

    public AlertController(AlertRuleService alerts) {
        this.alerts = alerts;
    }

    /** 规则列表（含内置默认规则）。 */
    @GetMapping("/rules")
    @RequirePermission(Permissions.OPS_MONITOR)
    public ApiResponse<List<AlertRuleView>> listRules() {
        return ApiResponse.ok(alerts.listRules());
    }

    @GetMapping("/rules/{id}")
    @RequirePermission(Permissions.OPS_MONITOR)
    public ApiResponse<AlertRuleView> getRule(@PathVariable String id) {
        return ApiResponse.ok(alerts.getRule(id));
    }

    @PostMapping("/rules")
    @RequirePermission(Permissions.OPS_ALERT_ADMIN)
    @Audited(action = "alert.rule.create", resourceType = "alert_rule")
    public ApiResponse<AlertRuleView> create(@RequestBody AlertRuleCommand body) {
        return ApiResponse.ok(alerts.create(body));
    }

    @PutMapping("/rules/{id}")
    @RequirePermission(Permissions.OPS_ALERT_ADMIN)
    @Audited(action = "alert.rule.update", resourceType = "alert_rule", resourceIdArg = "#id")
    public ApiResponse<AlertRuleView> update(@PathVariable String id,
                                             @RequestBody AlertRuleCommand body) {
        return ApiResponse.ok(alerts.update(id, body));
    }

    /** 启停（内置规则只允许停用，不允许删除）。 */
    @PostMapping("/rules/{id}/toggle")
    @RequirePermission(Permissions.OPS_ALERT_ADMIN)
    @Audited(action = "alert.rule.toggle", resourceType = "alert_rule", resourceIdArg = "#id")
    public ApiResponse<AlertRuleView> toggle(@PathVariable String id,
                                             @RequestParam(defaultValue = "true") boolean enabled) {
        return ApiResponse.ok(alerts.toggle(id, enabled));
    }

    @DeleteMapping("/rules/{id}")
    @RequirePermission(Permissions.OPS_ALERT_ADMIN)
    @Audited(action = "alert.rule.delete", resourceType = "alert_rule", resourceIdArg = "#id")
    public ApiResponse<Void> delete(@PathVariable String id) {
        alerts.delete(id);
        return ApiResponse.ok(null);
    }

    /** 告警事件历史（按规则 / 对象 / 状态过滤，时间倒序）。 */
    @GetMapping("/events")
    @RequirePermission(Permissions.OPS_MONITOR)
    public ApiResponse<List<AlertEventView>> listEvents(
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) AlertState state,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(alerts.listEvents(ruleId, targetId, state, limit));
    }
}
