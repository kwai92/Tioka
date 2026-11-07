package com.tioka.runtime.api.approval;

import java.util.Map;
import java.util.Optional;

/**
 * 读取 Agent 版本快照中的审批配置（供 {@code agent-config} 策略）。
 *
 * <p>AST-01 落地 {@code AgentSnapshotApi} 后由其实现；此前返回 empty，策略退回按
 * {@link ToolRisk} 默认：READ→auto，其余→approve。
 */
public interface AgentApprovalSettingsLookup {

    /**
     * @return toolPolicies：工具名 → {@code auto|approve|deny}；empty 表示无配置
     */
    Optional<Map<String, String>> toolPolicies(String agentVersionId);
}
