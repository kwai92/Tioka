package com.tioka.system.application.obs;

import java.util.Map;
import java.util.Set;

/**
 * 审计动作目录（{@code docs/contracts/audit/actions.yaml}）中 {@code category: SECURITY} 的子集
 * ——时间线 {@code SECURITY} 条目的来源动作白名单（OBS-08 / ADR-075 §2）。
 *
 * <p>动作目录是登记制契约（ADR-072 §2），本类只落「时间线要投影」的那一类，
 * 由 {@code M3Obs08TimelineContractTest} 与目录逐条比对，防止两处漂移。
 */
public final class SecurityAuditActions {

    /** 动作名 → 人读标题（时间线 {@code title}）。 */
    private static final Map<String, String> LABELS = Map.of(
            "api_key.create", "创建 API Key",
            "api_key.revoke", "吊销 API Key",
            "egress.denied", "出网被拒",
            "http.access_denied", "访问被拒",
            "permission.denied", "权限不足");

    /** 白名单动作名（时间线 SECURITY 条目的查询条件）。 */
    public static Set<String> actions() {
        return LABELS.keySet();
    }

    public static boolean isSecurity(String action) {
        return action != null && LABELS.containsKey(action);
    }

    /** 人读标题；未登记动作回落动作名本身（不丢信息）。 */
    public static String label(String action) {
        return action == null ? null : LABELS.getOrDefault(action, action);
    }

    private SecurityAuditActions() {
    }
}
