package com.tioka.runtime.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批台查询（HCI-05b）；仅 SELECT，不改决定路径。
 * <p>旁路 ScopeLine：{@code runtime_approval_assignee} 无 workspace_id；本 Mapper SQL 已显式按
 * {@code a.workspace_id} 过滤。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface RuntimeApprovalQueryMapper {

    List<RuntimeApprovalDO> list(@Param("workspaceId") String workspaceId,
                                 @Param("scope") String scope,
                                 @Param("userId") String userId,
                                 @Param("status") String status,
                                 @Param("subjectType") String subjectType,
                                 @Param("agentId") String agentId,
                                 @Param("risk") String risk,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to,
                                 @Param("keyword") String keyword,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    long count(@Param("workspaceId") String workspaceId,
               @Param("scope") String scope,
               @Param("userId") String userId,
               @Param("status") String status,
               @Param("subjectType") String subjectType,
               @Param("agentId") String agentId,
               @Param("risk") String risk,
               @Param("from") LocalDateTime from,
               @Param("to") LocalDateTime to,
               @Param("keyword") String keyword);

    RuntimeApprovalDO findById(@Param("id") String id);

    List<String> listAssigneeUserIds(@Param("approvalId") String approvalId);
}
