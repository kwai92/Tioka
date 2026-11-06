package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.approval.ApprovalSubjectHandler;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalKind;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RuntimeApi.decideApproval 实现（M1-02）。
 */
@Service
public class ApprovalDecideService {
    private final ApprovalRepository approvals;
    private final TurnRepository turns;
    private final RuntimeSessionRepository sessions;
    private final PrincipalHolder principals;
    private final ToolRegistry tools;
    private final Map<String, ApprovalSubjectHandler> handlers;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final ObjectMapper mapper;
    private final ObjectProvider<AuditApi> auditApi;

    public ApprovalDecideService(ApprovalRepository approvals,
                                 TurnRepository turns,
                                 RuntimeSessionRepository sessions,
                                 PrincipalHolder principals,
                                 ToolRegistry tools,
                                 List<ApprovalSubjectHandler> handlerList,
                                 EventStream streams,
                                 RuntimeEventFactory events,
                                 ObjectMapper mapper,
                                 ObjectProvider<AuditApi> auditApi) {
        this.approvals = approvals;
        this.turns = turns;
        this.sessions = sessions;
        this.principals = principals;
        this.tools = tools;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(h -> h.subjectType().toLowerCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));
        this.streams = streams;
        this.events = events;
        this.mapper = mapper;
        this.auditApi = auditApi;
    }

    @Transactional
    public void decide(String approvalId, RuntimeApi.ApprovalDecision decision) {
        RuntimeApprovalDO a = approvals.findForUpdate(approvalId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批不存在"));
        Principal p = principals.current();
        guardPermission(p);
        guardAssignee(a, p);

        ApprovalStatus current = ApprovalStatus.valueOf(a.getStatus());
        boolean wantApproved = decision.approved();
        ApprovalStatus wanted = wantApproved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;

        if (current.isTerminal()) {
            if (current == wanted) {
                return; // 同结果幂等
            }
            throw new BizException(ErrorCode.CONFLICT, "审批已决定为 " + current);
        }

        Map<String, Object> answer = new HashMap<>();
        if (decision.answer() != null) {
            answer.putAll(decision.answer());
        }
        if (decision.comment() != null) {
            answer.put("comment", decision.comment());
        }

        if ("tool".equalsIgnoreCase(a.getKind()) && answer.containsKey("args")) {
            validateEditedArgs(a, answer.get("args"));
        }

        String answerJson = json(answer);
        int n = approvals.decide(approvalId, wanted.name(), p.userId(), answerJson);
        if (n == 0) {
            // 并发下被别人先决定
            RuntimeApprovalDO again = approvals.findById(approvalId).orElseThrow();
            ApprovalStatus st = ApprovalStatus.valueOf(again.getStatus());
            if (st == wanted) {
                return;
            }
            throw new BizException(ErrorCode.CONFLICT, "审批已决定为 " + st);
        }

        a.setStatus(wanted.name());
        a.setDecidedBy(p.userId());
        a.setAnswerJson(answerJson);

        auditDecide(p, a, wanted);
        ApprovalView view = toView(a);
        String threadId = resolveThreadId(a);
        afterCommit(() -> {
            streams.publish(threadId, null, RuntimeEventType.APPROVAL_DECIDED.wire(),
                    events.approvalDecided(threadId, a.getSubjectId(), a.getId(),
                            a.getSubjectType(), a.getSubjectId(), a.getKind(),
                            wanted.name(), p.userId()));
            ApprovalSubjectHandler h = handlers.get(a.getSubjectType().toLowerCase(Locale.ROOT));
            if (h != null) {
                h.onDecided(view);
            }
        });
    }

    public ApprovalView get(String approvalId) {
        RuntimeApprovalDO a = approvals.findById(approvalId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "审批不存在"));
        Principal p = principals.current();
        guardPermission(p);
        return toView(a);
    }

    /** 过期 / interrupt 后通知 handler。 */
    public void notifyDecided(RuntimeApprovalDO a) {
        ApprovalView view = toView(a);
        ApprovalSubjectHandler h = handlers.get(a.getSubjectType().toLowerCase(Locale.ROOT));
        if (h != null) {
            h.onDecided(view);
        }
        String threadId = resolveThreadId(a);
        streams.publish(threadId, null, RuntimeEventType.APPROVAL_DECIDED.wire(),
                events.approvalDecided(threadId, a.getSubjectId(), a.getId(),
                        a.getSubjectType(), a.getSubjectId(), a.getKind(),
                        a.getStatus(), a.getDecidedBy()));
    }

    private void validateEditedArgs(RuntimeApprovalDO a, Object argsObj) {
        if (!(argsObj instanceof Map<?, ?>)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "answer.args 须为对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) argsObj;
        String agentVersionId = turns.findById(a.getSubjectId())
                .map(RuntimeTurnDO::getSessionId)
                .map(sid -> {
                    RuntimeSessionDO s = sessions.find(sid);
                    return s == null ? null : s.getAgentVersionId();
                })
                .orElse("");
        ToolProvider.ToolDescriptor desc = tools.listAll(agentVersionId).stream()
                .filter(d -> d.name().equals(a.getToolName()))
                .findFirst()
                .orElse(null);
        if (desc == null || desc.inputSchema() == null || desc.inputSchema().isEmpty()) {
            return;
        }
        try {
            JsonNode schemaNode = mapper.valueToTree(desc.inputSchema());
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
            JsonNode instance = mapper.valueToTree(args);
            Set<ValidationMessage> errors = schema.validate(instance);
            if (!errors.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "修改后的参数不符合 schema: " + errors);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数 schema 校验失败: " + e.getMessage());
        }
    }

    private void guardPermission(Principal p) {
        if (p == null || !p.hasPermission(Permissions.APPROVAL_DECIDE)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少 approval:decide");
        }
    }

    private void guardAssignee(RuntimeApprovalDO a, Principal p) {
        Map<String, Object> assignee = parseMap(a.getAssigneeJson());
        @SuppressWarnings("unchecked")
        List<String> userIds = assignee.get("userIds") instanceof List<?> l
                ? (List<String>) l : List.of();
        @SuppressWarnings("unchecked")
        List<String> roleCodes = assignee.get("roleCodes") instanceof List<?> l
                ? (List<String>) l : List.of();
        if (userIds.isEmpty() && roleCodes.isEmpty()) {
            // 空 = Turn 发起人
            RuntimeTurnDO turn = turns.findById(a.getSubjectId()).orElse(null);
            String requester = turn == null ? null : extractRunAsUser(turn.getRunAsJson());
            if (requester != null && requester.equals(p.userId())) {
                return;
            }
            // tenant_admin / 有审批权限且是发起人空间成员也放行：若无法解析发起人则仅本人
            if (requester == null) {
                return;
            }
            throw new BizException(ErrorCode.FORBIDDEN, "非审批人");
        }
        if (userIds.contains(p.userId())) {
            return;
        }
        for (String role : roleCodes) {
            if (p.roles().contains(role)) {
                return;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN, "非审批人");
    }

    private String extractRunAsUser(String runAsJson) {
        try {
            JsonNode n = mapper.readTree(runAsJson == null ? "{}" : runAsJson);
            return n.path("userId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveThreadId(RuntimeApprovalDO a) {
        if (!ApprovalSubjectType.TURN.wire().equals(a.getSubjectType())) {
            return a.getSubjectId();
        }
        return turns.findById(a.getSubjectId()).map(RuntimeTurnDO::getSessionId).orElse(a.getSubjectId());
    }

    private void auditDecide(Principal p, RuntimeApprovalDO a, ApprovalStatus status) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        api.record(new AuditEvent(null, Instant.now(), null, a.getWorkspaceId(),
                p.type().name().toLowerCase(Locale.ROOT), p.userId(), "approval.decide",
                "runtime_approval", a.getId(), AuditOutcome.SUCCESS, status.name(), null, null,
                Map.of("approved", status == ApprovalStatus.APPROVED)));
    }

    private ApprovalView toView(RuntimeApprovalDO a) {
        Map<String, Object> assignee = parseMap(a.getAssigneeJson());
        @SuppressWarnings("unchecked")
        List<String> userIds = assignee.get("userIds") instanceof List<?> l ? (List<String>) l : List.of();
        @SuppressWarnings("unchecked")
        List<String> roles = assignee.get("roleCodes") instanceof List<?> l ? (List<String>) l : List.of();
        Map<String, Object> answer = parseMap(a.getAnswerJson());
        String comment = answer.get("comment") == null ? null : String.valueOf(answer.get("comment"));
        ToolRisk risk = null;
        try {
            Map<String, Object> card = parseMap(a.getCardJson());
            if (card.get("risk") != null) {
                risk = ToolRisk.valueOf(String.valueOf(card.get("risk")));
            }
        } catch (Exception ignored) {
        }
        return new ApprovalView(
                a.getId(),
                parseSubjectType(a.getSubjectType()),
                a.getSubjectId(),
                "card".equalsIgnoreCase(a.getKind()) ? ApprovalKind.CARD : ApprovalKind.TOOL,
                a.getToolName(),
                null,
                a.getToolName(),
                parseMap(a.getArgsJson()),
                parseMap(a.getCardJson()),
                risk,
                ApprovalStatus.valueOf(a.getStatus()),
                a.getWorkspaceId(),
                a.getCreateBy(),
                userIds,
                roles,
                a.getDecidedBy(),
                a.getDecidedAt() == null ? null : a.getDecidedAt().toInstant(),
                answer,
                comment,
                a.getExpiresAt() == null ? null : a.getExpiresAt().toInstant(),
                a.getCreateAt() == null ? null : a.getCreateAt().toInstant(),
                null);
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

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

}
