package com.tioka.runtime.kernel.approval;

import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.runtime.api.approval.AgentApprovalSettingsLookup;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AST-01：从 Agent 快照读 approval.toolPolicies，供 RT-07 agent-config 策略使用。
 */
@Component
public class AgentSnapshotApprovalSettingsLookup implements AgentApprovalSettingsLookup {
    private final AgentSnapshotApi snapshots;

    public AgentSnapshotApprovalSettingsLookup(AgentSnapshotApi snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Optional<Map<String, String>> toolPolicies(String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) {
            return Optional.empty();
        }
        try {
            AgentSpec.ApprovalSettings approval = snapshots.get(agentVersionId).spec().approval();
            if (approval == null || approval.toolPolicies() == null || approval.toolPolicies().isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> normalized = approval.toolPolicies().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue() == null ? "" : e.getValue().trim().toLowerCase(Locale.ROOT)));
            return Optional.of(normalized);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
