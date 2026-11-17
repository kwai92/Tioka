package com.tioka.governance.infrastructure.alert;

import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;

/**
 * 告警规则的 {@code cfg_change} 流水写入（GOV-13 / ADR-041 §1：即时型对象的变更记录）。
 *
 * <p>只写不改；由 {@code AlertAutoConfiguration} 装配，application 经本类写流水。
 */
public class AlertChangeLog {

    /** 变更记录中的对象类型（与 {@code ConfigVersionApi.ObjectRef.objectType} 同风格）。 */
    public static final String OBJECT_TYPE = "ALERT_RULE";

    private final CfgChangeMapper changes;

    public AlertChangeLog(CfgChangeMapper changes) {
        this.changes = changes;
    }

    /** 记录一次规则变更（{@code beforeJson} 为新建时的 {@code null}）。 */
    public void record(String targetKey, String beforeJson, String afterJson,
                       String actorId, String reason, String traceId) {
        changes.insert(OBJECT_TYPE, targetKey, beforeJson, afterJson, actorId, reason, traceId);
    }
}
