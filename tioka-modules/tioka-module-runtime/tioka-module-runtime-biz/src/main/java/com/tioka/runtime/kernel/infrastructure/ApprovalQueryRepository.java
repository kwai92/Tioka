package com.tioka.runtime.kernel.infrastructure;

import com.tioka.runtime.infrastructure.mapper.RuntimeApprovalQueryMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * HCI-05b：审批台查询仓储；封装 {@link RuntimeApprovalQueryMapper}。
 */
@Repository
public class ApprovalQueryRepository {
    private final RuntimeApprovalQueryMapper mapper;

    public ApprovalQueryRepository(RuntimeApprovalQueryMapper mapper) {
        this.mapper = mapper;
    }

    public List<RuntimeApprovalDO> list(String workspaceId, String scope, String userId, String status,
                                        String subjectType, String agentId, String risk,
                                        LocalDateTime from, LocalDateTime to, String keyword,
                                        int offset, int limit) {
        return mapper.list(workspaceId, scope, userId, status, subjectType, agentId, risk,
                from, to, keyword, offset, limit);
    }

    public long count(String workspaceId, String scope, String userId, String status,
                      String subjectType, String agentId, String risk,
                      LocalDateTime from, LocalDateTime to, String keyword) {
        return mapper.count(workspaceId, scope, userId, status, subjectType, agentId, risk,
                from, to, keyword);
    }

    public Optional<RuntimeApprovalDO> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public List<String> listAssigneeUserIds(String approvalId) {
        List<String> ids = mapper.listAssigneeUserIds(approvalId);
        return ids == null ? List.of() : ids;
    }
}
