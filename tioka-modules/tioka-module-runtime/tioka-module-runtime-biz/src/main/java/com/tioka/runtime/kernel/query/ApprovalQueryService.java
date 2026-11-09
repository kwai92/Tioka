package com.tioka.runtime.kernel.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.approval.ApprovalQueryApi;
import com.tioka.runtime.api.approval.ApprovalSubjectLinker;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalKind;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.application.changeset.ChangeSetCardService;
import com.tioka.runtime.kernel.infrastructure.ApprovalQueryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HCI-05b：审批台查询；不改决定逻辑。
 */
@Service
public class ApprovalQueryService implements ApprovalQueryApi {

    private final ApprovalQueryRepository approvals;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final ChangeSetCardService changeSetCards;
    private final Map<String, ApprovalSubjectLinker> linkers;

    public ApprovalQueryService(ApprovalQueryRepository approvals,
                                PrincipalHolder principals,
                                ObjectMapper mapper,
                                ChangeSetCardService changeSetCards,
                                List<ApprovalSubjectLinker> linkerList) {
        this.approvals = approvals;
        this.principals = principals;
        this.mapper = mapper;
        this.changeSetCards = changeSetCards;
        this.linkers = linkerList.stream()
                .collect(Collectors.toMap(l -> l.subjectType().toLowerCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));
    }

    @Override
    public Page list(ApprovalQuery query) {
        Principal p = requirePrincipal();
        Scope scope = query.scope() == null ? Scope.ASSIGNED_TO_ME : query.scope();
        guardScope(scope, p);
        int page = Math.max(1, query.page() <= 0 ? 1 : query.page());
        int size = Math.min(100, Math.max(1, query.size() <= 0 ? 20 : query.size()));
        String workspaceId = p.workspaceId();
        String status = query.status() == null ? null : query.status().name();
        String subjectType = query.subjectType() == null ? null : query.subjectType().wire();
        String risk = query.risk() == null ? null : query.risk().name();
        LocalDateTime from = toLdt(query.from());
        LocalDateTime to = toLdt(query.to());
        long total = approvals.count(workspaceId, scope.name(), p.userId(), status, subjectType,
                query.agentId(), risk, from, to, query.keyword());
        List<ApprovalView> items = approvals.list(workspaceId, scope.name(), p.userId(), status, subjectType,
                        query.agentId(), risk, from, to, query.keyword(), (page - 1) * size, size)
                .stream()
                .map(this::toView)
                .toList();
        return new Page(items, total, page, size);
    }

    @Override
    public ApprovalView get(String approvalId) {
        requirePrincipal();
        RuntimeApprovalDO row = approvals.findById(approvalId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批不存在"));
        Principal p = principals.current();
        if (p.workspaceId() != null && row.getWorkspaceId() != null
                && !p.workspaceId().equals(row.getWorkspaceId())
                && !p.hasPermission(Permissions.ALL)
                && !p.hasPermission(Permissions.TENANT_ADMIN)) {
            throw new BizException(ErrorCode.NOT_FOUND, "审批不存在");
        }
        return toView(row);
    }

    @Override
    public long count(Scope scope, ApprovalStatus status) {
        Principal p = requirePrincipal();
        Scope s = scope == null ? Scope.ASSIGNED_TO_ME : scope;
        guardScope(s, p);
        String st = status == null ? null : status.name();
        return approvals.count(p.workspaceId(), s.name(), p.userId(), st, null, null, null, null, null, null);
    }

    @Override
    public Optional<ChangeSetDetail> changeSet(String approvalId) {
        requirePrincipal();
        // 工作区隔离：与 get() 同口径，跨空间按不存在处理
        RuntimeApprovalDO a = approvals.findById(approvalId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批不存在"));
        Principal p = principals.current();
        if (p.workspaceId() != null && a.getWorkspaceId() != null
                && !p.workspaceId().equals(a.getWorkspaceId())
                && !p.hasPermission(Permissions.ALL)
                && !p.hasPermission(Permissions.TENANT_ADMIN)) {
            throw new BizException(ErrorCode.NOT_FOUND, "审批不存在");
        }
        return changeSetCards.detailByApproval(approvalId);
    }

    private void guardScope(Scope scope, Principal p) {
        if (scope == Scope.WORKSPACE_ALL
                && !p.hasPermission(Permissions.WORKSPACE_MANAGE)
                && !p.hasPermission(Permissions.ALL)
                && !p.hasPermission(Permissions.TENANT_ADMIN)) {
            throw new BizException(ErrorCode.FORBIDDEN, "WORKSPACE_ALL 需要 workspace:manage");
        }
    }

    private Principal requirePrincipal() {
        Principal p = principals.current();
        if (p == null || p.userId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return p;
    }

    private ApprovalView toView(RuntimeApprovalDO a) {
        Map<String, Object> assignee = parseMap(a.getAssigneeJson());
        @SuppressWarnings("unchecked")
        List<String> roles = assignee.get("roleCodes") instanceof List<?> l ? (List<String>) l : List.of();
        List<String> userIds = approvals.listAssigneeUserIds(a.getId());
        if (userIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> fromJson = assignee.get("userIds") instanceof List<?> l ? (List<String>) l : List.of();
            userIds = fromJson;
        }
        Map<String, Object> answer = parseMap(a.getAnswerJson());
        String comment = answer.get("comment") == null ? null : String.valueOf(answer.get("comment"));
        ToolRisk risk = parseRisk(a.getRisk(), a.getCardJson());
        String title = a.getTitle() != null && !a.getTitle().isBlank() ? a.getTitle() : a.getToolName();
        String requestedBy = a.getRequestedBy() != null ? a.getRequestedBy() : a.getCreateBy();
        ApprovalSubjectType subjectType = parseSubjectType(a.getSubjectType());
        ApprovalView.SubjectLink link = resolveLink(subjectType.wire(), a.getSubjectId(), title);
        return new ApprovalView(
                a.getId(),
                subjectType,
                a.getSubjectId(),
                "card".equalsIgnoreCase(a.getKind()) ? ApprovalKind.CARD : ApprovalKind.TOOL,
                title,
                null,
                a.getToolName(),
                parseMap(a.getArgsJson()),
                parseMap(a.getCardJson()),
                risk,
                ApprovalStatus.valueOf(a.getStatus()),
                a.getWorkspaceId(),
                requestedBy,
                userIds,
                roles,
                a.getDecidedBy(),
                a.getDecidedAt() == null ? null : a.getDecidedAt().toInstant(ZoneOffset.UTC),
                answer,
                comment,
                a.getExpiresAt() == null ? null : a.getExpiresAt().toInstant(ZoneOffset.UTC),
                a.getCreateAt() == null ? null : a.getCreateAt().toInstant(ZoneOffset.UTC),
                link);
    }

    private ApprovalView.SubjectLink resolveLink(String subjectType, String subjectId, String titleHint) {
        ApprovalSubjectLinker linker = linkers.get(subjectType.toLowerCase(Locale.ROOT));
        if (linker != null) {
            return linker.link(subjectId, titleHint);
        }
        return new ApprovalView.SubjectLink(subjectType, subjectId, titleHint, null);
    }

    private ToolRisk parseRisk(String riskCol, String cardJson) {
        if (riskCol != null && !riskCol.isBlank()) {
            try {
                return ToolRisk.valueOf(riskCol);
            } catch (Exception ignored) {
            }
        }
        try {
            Map<String, Object> card = parseMap(cardJson);
            if (card.get("risk") != null) {
                return ToolRisk.valueOf(String.valueOf(card.get("risk")));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ApprovalSubjectType parseSubjectType(String wire) {
        for (ApprovalSubjectType t : ApprovalSubjectType.values()) {
            if (t.wire().equalsIgnoreCase(wire) || t.name().equalsIgnoreCase(wire)) {
                return t;
            }
        }
        return ApprovalSubjectType.TURN;
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static LocalDateTime toLdt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
