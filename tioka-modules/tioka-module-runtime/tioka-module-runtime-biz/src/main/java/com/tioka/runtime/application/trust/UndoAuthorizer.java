package com.tioka.runtime.application.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 可撤销人判定（TRU-07 / ADR-033）：Turn 发起人、编排工单请求人、Agent 负责人、
 * 批准过该动作的审批人、有 {@code trust:undo} 权限的空间管理员。
 *
 * <p>M3 简化（登记 M3-C4 设计笔记）：空间管理员以 {@code workspace:manage} 权限点判定
 * （不新增权限点目录与角色迁移）；「编排工单请求人」经 {@link RunRequesterLookup} SPI，
 * 无实现时跳过（runtime 不读 orch_mandate，M4 补）。
 */
@Component
public class UndoAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(UndoAuthorizer.class);

    private final TurnRepository turns;
    private final ApprovalRepository approvals;
    private final ObjectProvider<AgentSnapshotApi> agentSnapshots;
    private final Optional<RunRequesterLookup> runRequesters;
    private final ObjectMapper mapper;

    public UndoAuthorizer(TurnRepository turns,
                          ApprovalRepository approvals,
                          ObjectProvider<AgentSnapshotApi> agentSnapshots,
                          Optional<RunRequesterLookup> runRequesters,
                          ObjectMapper mapper) {
        this.turns = turns;
        this.approvals = approvals;
        this.agentSnapshots = agentSnapshots;
        this.runRequesters = runRequesters;
        this.mapper = mapper;
    }

    /** 任一身份命中即放行；否则 403。 */
    public void check(Principal principal, String scopeId, List<TrustActionDO> rows) {
        if (principal == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "撤销需要登录身份");
        }
        if (principal.hasPermission(Permissions.WORKSPACE_MANAGE)) {
            return; // 空间管理员（trust:undo 口径，M3 简化）
        }
        for (TrustActionDO row : rows) {
            if (isTurnInitiator(principal.userId(), row.getTurnId())
                    || isApprover(principal.userId(), row.getApprovalId())
                    || isAgentOwner(principal.userId(), row.getAgentId(), row.getAgentVersionId())
                    || isRunRequester(principal.userId(), row.getOrchRunId())) {
                return;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN, "仅 Turn 发起人、工单请求人、Agent 负责人、"
                + "批准过该动作的审批人或空间管理员可撤销");
    }

    private boolean isTurnInitiator(String userId, String turnId) {
        if (userId == null || turnId == null || turnId.isBlank()) {
            return false;
        }
        Optional<RuntimeTurnDO> turn = turns.findById(turnId);
        if (turn.isEmpty()) {
            return false;
        }
        try {
            var json = mapper.readTree(turn.get().getRunAsJson());
            return userId.equals(json.path("userId").asText(null));
        } catch (Exception e) {
            log.debug("run_as_json 解析失败 turn={}: {}", turnId, e.toString());
            return false;
        }
    }

    private boolean isApprover(String userId, String approvalId) {
        if (userId == null || approvalId == null || approvalId.isBlank()) {
            return false;
        }
        return approvals.findById(approvalId)
                .map(a -> userId.equals(a.getDecidedBy()))
                .orElse(false);
    }

    private boolean isAgentOwner(String userId, String agentId, String agentVersionId) {
        AgentSnapshotApi snapshots = agentSnapshots.getIfAvailable();
        if (userId == null || snapshots == null) {
            return false;
        }
        try {
            return userId.equals(snapshots.currentPublished(agentId).ownerId());
        } catch (RuntimeException e) {
            try {
                return userId.equals(snapshots.get(agentVersionId).ownerId());
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }

    private boolean isRunRequester(String userId, String orchRunId) {
        return runRequesters.map(lookup -> lookup.isRequester(userId, orchRunId)).orElse(false);
    }
}
