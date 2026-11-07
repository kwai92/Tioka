package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ApprovalSubjectType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务内插入 runtime_approval、写 card item、Turn → WAITING_APPROVAL（M1-02）。
 */
@Service
public class ApprovalSuspendService {
    private final ApprovalRepository approvals;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final EventStream streams;
    private final RuntimeEventFactory events;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public ApprovalSuspendService(ApprovalRepository approvals,
                                  TurnRepository turns,
                                  ItemRepository items,
                                  EventStream streams,
                                  RuntimeEventFactory events,
                                  PrincipalHolder principals,
                                  ObjectMapper mapper) {
        this.approvals = approvals;
        this.turns = turns;
        this.items = items;
        this.streams = streams;
        this.events = events;
        this.principals = principals;
        this.mapper = mapper;
    }

    @Transactional
    public void suspend(LoopContext ctx, List<PendingToolCall> requires) {
        if (requires == null || requires.isEmpty()) {
            return;
        }
        String workspaceId = ctx.workspaceId();
        String policyCode = ctx.approvalPolicy() == null || ctx.approvalPolicy().isBlank()
                ? AgentConfigApprovalPolicy.CODE : ctx.approvalPolicy();
        int step = 0;
        for (PendingToolCall p : requires) {
            if (!(p.decision() instanceof ApprovalPolicy.RequireApproval req)) {
                continue;
            }
            String approvalId = Ids.newId("apv");
            Duration ttl = req.ttl() == null ? Duration.ofHours(24) : req.ttl();
            Instant expires = Instant.now().plus(ttl);
            ToolRisk risk = p.descriptor() == null || p.descriptor().risk() == null
                    ? ToolRisk.WRITE : p.descriptor().risk();

            RuntimeApprovalDO row = new RuntimeApprovalDO();
            row.setId(approvalId);
            row.setWorkspaceId(workspaceId);
            row.setSubjectType(ApprovalSubjectType.TURN.wire());
            row.setSubjectId(ctx.turnId());
            row.setItemId(p.itemId());
            row.setKind("tool");
            row.setToolName(p.call().name());
            row.setArgsJson(json(p.args()));
            row.setPolicy(policyCode);
            row.setAssigneeJson(json(Map.of(
                    "userIds", req.assignee().userIds(),
                    "roleCodes", req.assignee().roleCodes())));
            row.setStatus(ApprovalStatus.PENDING.name());
            row.setExpiresAt(LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
            String by = principals.current() == null ? "system" : principals.current().userId();
            row.setCreateBy(by);
            row.setUpdateBy(by);
            approvals.insert(row);

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("kind", "tool");
            card.put("approvalId", approvalId);
            card.put("toolName", p.call().name());
            card.put("args", p.args());
            card.put("risk", risk.name());
            card.put("reason", req.reason());
            RuntimeItemDO cardItem = items.append(ItemWrite.append(ctx.threadId(), ctx.turnId(), ctx.leaseVersion(), step,
                    ItemType.CARD.wire(), "system", json(card), null));
            streams.publish(ctx.threadId(), EventId.of(cardItem.getSeq()).toString(),
                    RuntimeEventType.ITEM_COMPLETED.wire(),
                    events.itemCompleted(ctx.threadId(), cardItem));

            streams.publish(ctx.threadId(), null, RuntimeEventType.APPROVAL_REQUESTED.wire(),
                    events.approvalRequested(ctx.threadId(), ctx.turnId(), approvalId,
                            ApprovalSubjectType.TURN.wire(), ctx.turnId(), "tool",
                            ApprovalStatus.PENDING.name(), p.call().name(), risk.name()));
        }
        int n = turns.markWaitingApproval(ctx.turnId(), ctx.leaseVersion());
        if (n == 0) {
            throw new BizException(ErrorCode.LEASE_LOST, "挂起审批时租约丢失");
        }
        streams.publish(ctx.threadId(), null, RuntimeEventType.TURN_WAITING.wire(),
                events.turnStatus(RuntimeEventType.TURN_WAITING, ctx.threadId(), ctx.turnId(),
                        TurnStatus.WAITING_APPROVAL.name()));
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
