package com.tioka.governance.application.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.foundation.common.id.Ids;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertEventView;
import com.tioka.governance.api.alert.AlertRuleCommand;
import com.tioka.governance.api.alert.AlertRuleView;
import com.tioka.governance.api.alert.AlertState;
import com.tioka.governance.infrastructure.alert.AlertChangeLog;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * GOV-13 告警规则管理（ADR-041 §5.3）。
 *
 * <p>即时型配置对象：每次改动写 {@code cfg_change} 流水（改前 / 改后 / 谁 / 为什么），
 * 供「变更记录」界面查看。读需 {@code ops:monitor}，写需 {@code ops:alert:admin}。
 */
public class AlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleService.class);

    private final OpsAlertRepository repo;
    private final AlertChangeLog changeLog;
    private final AlertProperties props;
    private final PrincipalHolder principals;
    private final ObjectMapper objectMapper;

    public AlertRuleService(OpsAlertRepository repo,
                            AlertChangeLog changeLog,
                            AlertProperties props,
                            PrincipalHolder principals,
                            ObjectMapper objectMapper) {
        this.repo = repo;
        this.changeLog = changeLog;
        this.props = props;
        this.principals = principals;
        this.objectMapper = objectMapper;
    }

    // ---------- 查询 ----------

    public List<AlertRuleView> listRules() {
        require(Permissions.OPS_MONITOR);
        List<AlertRuleView> out = new ArrayList<>();
        for (OpsAlertRuleDO row : repo.listAllRules()) {
            out.add(AlertViews.toView(row));
        }
        return out;
    }

    public AlertRuleView getRule(String id) {
        require(Permissions.OPS_MONITOR);
        return AlertViews.toView(requireRule(id));
    }

    public List<AlertEventView> listEvents(String ruleId, String targetId, AlertState state, Integer limit) {
        require(Permissions.OPS_MONITOR);
        int n = limit == null ? props.getEventQueryLimit()
                : Math.max(1, Math.min(limit, props.getEventQueryLimit()));
        List<AlertEventView> out = new ArrayList<>();
        for (var row : repo.listRecentEvents(ruleId, targetId, state == null ? null : state.name(), n)) {
            out.add(AlertViews.toEventView(row));
        }
        return out;
    }

    // ---------- 管理 ----------

    public AlertRuleView create(AlertRuleCommand command) {
        require(Permissions.OPS_ALERT_ADMIN);
        validate(command);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String actor = actorId();
        OpsAlertRuleDO row = new OpsAlertRuleDO();
        row.setId(Ids.newId("alrt"));
        apply(row, command, actor, now, false);
        row.setCreateAt(now);
        row.setCreateBy(actor);
        repo.insertRule(row);
        changeLog.record(row.getId(), null, json(AlertViews.toView(row)), actor, "create", null);
        return AlertViews.toView(row);
    }

    public AlertRuleView update(String id, AlertRuleCommand command) {
        require(Permissions.OPS_ALERT_ADMIN);
        validate(command);
        OpsAlertRuleDO row = requireRule(id);
        String before = json(AlertViews.toView(row));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String actor = actorId();
        apply(row, command, actor, now, Boolean.TRUE.equals(row.getBuiltin()));
        repo.updateRule(row);
        changeLog.record(id, before, json(AlertViews.toView(row)), actor, "update", null);
        return AlertViews.toView(row);
    }

    /** 启停：内置规则只允许启停，不允许删除。 */
    public AlertRuleView toggle(String id, boolean enabled) {
        require(Permissions.OPS_ALERT_ADMIN);
        OpsAlertRuleDO row = requireRule(id);
        String before = json(AlertViews.toView(row));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String actor = actorId();
        row.setEnabled(enabled);
        row.setUpdateAt(now);
        row.setUpdateBy(actor);
        repo.updateRule(row);
        changeLog.record(id, before, json(AlertViews.toView(row)), actor, enabled ? "enable" : "disable", null);
        return AlertViews.toView(row);
    }

    public void delete(String id) {
        require(Permissions.OPS_ALERT_ADMIN);
        OpsAlertRuleDO row = requireRule(id);
        if (Boolean.TRUE.equals(row.getBuiltin())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内置告警规则不可删除，只能停用");
        }
        String before = json(AlertViews.toView(row));
        repo.deleteRule(id);
        changeLog.record(id, before, null, actorId(), "delete", null);
    }

    /**
     * 幂等播种内置默认规则（ADR-041 §5.3）。返回新增条数。
     *
     * <p>并发多实例同时播种时，唯一索引 {@code uk_alert_rule_builtin_name} 保证只有一方写入，
     * 冲突的一方忽略继续。
     */
    public int seedBuiltinRules() {
        int inserted = 0;
        for (AlertRuleCommand command : BuiltinAlertRules.defaults()) {
            if (repo.findBuiltinRuleByName(command.name()).isPresent()) {
                continue;
            }
            try {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                OpsAlertRuleDO row = new OpsAlertRuleDO();
                row.setId(Ids.newId("alrt"));
                apply(row, command, "system", now, true);
                row.setCreateAt(now);
                row.setCreateBy("system");
                repo.insertRule(row);
                changeLog.record(row.getId(), null, json(AlertViews.toView(row)), "system", "builtin", null);
                inserted++;
            } catch (RuntimeException e) {
                log.debug("内置告警规则播种跳过（可能已存在）: {}", command.name());
            }
        }
        return inserted;
    }

    // ---------- 内部 ----------

    private OpsAlertRuleDO requireRule(String id) {
        return repo.findRule(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "告警规则不存在: " + id));
    }

    private static void apply(OpsAlertRuleDO row, AlertRuleCommand c, String actor,
                              LocalDateTime now, boolean builtin) {
        row.setName(c.name().trim());
        row.setTargetType(c.targetType().name());
        row.setTargetId(blankToNull(c.targetId()));
        row.setConditionType(c.conditionType().name());
        row.setComparator(c.comparator().name());
        row.setThresholdValue(c.thresholdValue());
        row.setThresholdText(blankToNull(c.thresholdText()));
        row.setWindowMinutes(Math.max(0, c.windowMinutes()));
        row.setSeverity(c.severity().name());
        String channels = AlertViews.joinCsv(c.channels());
        row.setChannels(channels == null ? "in_app" : channels);
        row.setRecipients(AlertViews.joinCsv(c.recipients()));
        row.setSilenceStart(blankToNull(c.silenceStart()));
        row.setSilenceEnd(blankToNull(c.silenceEnd()));
        row.setEnabled(c.enabled());
        row.setBuiltin(builtin);
        row.setDescription(blankToNull(c.description()));
        row.setUpdateAt(now);
        row.setUpdateBy(actor);
    }

    private static void validate(AlertRuleCommand c) {
        if (c == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则内容不能为空");
        }
        if (c.name() == null || c.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则名称不能为空");
        }
        if (c.name().trim().length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规则名称过长（>128）");
        }
        if (c.targetType() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "监控对象类型不能为空");
        }
        if (c.conditionType() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "告警条件不能为空");
        }
        if (c.targetType() != com.tioka.governance.api.alert.AlertTargetType.ALL
                && (c.targetId() == null || c.targetId().isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非 ALL 监控对象必须指定 targetId");
        }
        if (c.severity() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "严重级别不能为空");
        }
        AlertConditionType type = c.conditionType();
        if (type.isStateCondition()) {
            if (c.thresholdText() == null || c.thresholdText().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "状态条件必须给出目标状态（thresholdText）");
            }
        } else if (type.isMetricCondition()) {
            if (c.comparator() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "指标条件必须给出比较符");
            }
            if (c.thresholdValue() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "指标条件必须给出阈值");
            }
        } else if (c.comparator() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "事件条件必须给出比较符占位");
        }
        boolean hasStart = c.silenceStart() != null && !c.silenceStart().isBlank();
        boolean hasEnd = c.silenceEnd() != null && !c.silenceEnd().isBlank();
        if (hasStart != hasEnd) {
            throw new BizException(ErrorCode.BAD_REQUEST, "静默时段起止必须同时给出");
        }
        if (hasStart && (!isHhMm(c.silenceStart()) || !isHhMm(c.silenceEnd()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "静默时段格式须为 HH:mm");
        }
        if (c.windowMinutes() < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "统计窗口不能为负");
        }
    }

    private static boolean isHhMm(String value) {
        try {
            java.time.LocalTime.parse(value.trim());
            return value.trim().length() == 5;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void require(String permission) {
        Principal p = principals == null ? null : principals.current();
        if (p == null || !p.hasPermission(permission)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少权限 " + permission);
        }
    }

    private String actorId() {
        Principal p = principals == null ? null : principals.find().orElse(null);
        return p == null ? "system" : p.userId();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
