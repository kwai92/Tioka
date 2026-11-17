package com.tioka.framework.security;

/**
 * 权限点目录（FND-04，施工设计 M1-01）。{@link RequirePermission} 只能引用这里的常量。
 *
 * <p>新增权限点必须同时改本类、迁移脚本中的角色授权与管理后台的权限矩阵。
 */
public final class Permissions {

    public static final String ALL = "*";

    public static final String AGENT_READ = "agent:read";
    public static final String AGENT_WRITE = "agent:write";
    public static final String AGENT_PUBLISH = "agent:publish";
    public static final String AGENT_SHARE = "agent:share";

    public static final String FLOW_READ = "flow:read";
    public static final String FLOW_WRITE = "flow:write";
    public static final String FLOW_PUBLISH = "flow:publish";
    public static final String FLOW_RUN = "flow:run";

    public static final String KB_READ = "kb:read";
    public static final String KB_WRITE = "kb:write";
    public static final String KB_SHARE = "kb:share";

    public static final String SKILL_READ = "skill:read";
    public static final String SKILL_WRITE = "skill:write";
    public static final String SKILL_PUBLISH = "skill:publish";

    public static final String MCP_READ = "mcp:read";
    public static final String MCP_WRITE = "mcp:write";
    public static final String MCP_PUBLISH = "mcp:publish";

    public static final String CHAT_USE = "chat:use";
    public static final String APPROVAL_DECIDE = "approval:decide";

    public static final String ORCH_READ = "orch:read";
    public static final String ORCH_WRITE = "orch:write";
    public static final String ORCH_OPERATE = "orch:operate";

    public static final String MODEL_READ = "model:read";
    public static final String MODEL_ADMIN = "model:admin";

    public static final String QUOTA_READ = "quota:read";
    public static final String QUOTA_ADMIN = "quota:admin";

    public static final String AUDIT_READ = "audit:read";
    /** 审计全局读取（跨空间）：ADR-072 §6 权限点细分（OBS-09）。 */
    public static final String AUDIT_READ_ALL = "audit:read:all";
    /** 审计导出：ADR-072 §6 权限点细分（OBS-09）；导出本身写审计。 */
    public static final String AUDIT_EXPORT = "audit:export";
    public static final String WORKSPACE_MANAGE = "workspace:manage";
    public static final String TENANT_ADMIN = "tenant:admin";
    /** 策略引擎管理：导入 / 模式切换 / 停用（GOV-07 / M3-05 §1.4）。 */
    public static final String GOV_POLICY_ADMIN = "gov:policy:admin";

    /** 密封集逐条内容读取（C-18 EVO-07 / ADR-068 L42/L47：仅评测负责人与业务专家）。 */
    public static final String EVAL_SEALED_READ = "eval:sealed:read";

    /** 时间线条目内容读取（OBS-08 / ADR-075 §4）：持有时可读 CONFIDENTIAL 及以上的条目。 */
    public static final String TRAJECTORY_READ_CONTENT = "trajectory:read:content";

    /** 查看运行状态与告警（GOV-13 / ADR-041 §8）。 */
    public static final String OPS_MONITOR = "ops:monitor";
    /** 管理告警规则（GOV-13 / ADR-041 §8）。 */
    public static final String OPS_ALERT_ADMIN = "ops:alert:admin";
    /** 查看供给侧统计（供应商、binding 维度的成本；ADR-041 §8）。 */
    public static final String STATS_READ = "stats:read";

    /** 目录中的全部具体权限点（不含 {@link #ALL}）。 */
    public static final String[] ALL_CONCRETE = {
            AGENT_READ, AGENT_WRITE, AGENT_PUBLISH, AGENT_SHARE,
            FLOW_READ, FLOW_WRITE, FLOW_PUBLISH, FLOW_RUN,
            KB_READ, KB_WRITE, KB_SHARE,
            SKILL_READ, SKILL_WRITE, SKILL_PUBLISH,
            MCP_READ, MCP_WRITE, MCP_PUBLISH,
            CHAT_USE, APPROVAL_DECIDE,
            ORCH_READ, ORCH_WRITE, ORCH_OPERATE,
            MODEL_READ, MODEL_ADMIN,
            QUOTA_READ, QUOTA_ADMIN,
            AUDIT_READ, AUDIT_READ_ALL, AUDIT_EXPORT, WORKSPACE_MANAGE, TENANT_ADMIN, GOV_POLICY_ADMIN,
            EVAL_SEALED_READ, TRAJECTORY_READ_CONTENT,
            OPS_MONITOR, OPS_ALERT_ADMIN, STATS_READ
    };

    private Permissions() {}
}
