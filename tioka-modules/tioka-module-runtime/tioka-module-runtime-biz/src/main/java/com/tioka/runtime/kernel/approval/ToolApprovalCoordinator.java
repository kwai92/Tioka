package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.approval.ApprovalRequestContexts;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AgentLoop 审批挂钩：启动时续跑孤儿 tool_call；一步内分类 Allow/Deny/Require。
 */
@Component
public class ToolApprovalCoordinator {
    private final ApprovalPolicyResolver policies;
    private final ToolRegistry tools;
    private final PrincipalHolder principals;
    private final ApprovalRepository approvals;
    private final ItemRepository items;
    private final ApprovalSuspendService suspendService;
    private final ObjectMapper mapper;

    public ToolApprovalCoordinator(ApprovalPolicyResolver policies,
                                   ToolRegistry tools,
                                   PrincipalHolder principals,
                                   ApprovalRepository approvals,
                                   ItemRepository items,
                                   ApprovalSuspendService suspendService,
                                   ObjectMapper mapper) {
        this.policies = policies;
        this.tools = tools;
        this.principals = principals;
        this.approvals = approvals;
        this.items = items;
        this.suspendService = suspendService;
        this.mapper = mapper;
    }

    /**
     * 续跑：对无 tool_result 的 tool_call，按审批状态写结果并（批准时）真正调工具。
     */
    public void resumeOrphans(LoopContext ctx, LoopPorts.ToolPort toolPort, LoopPorts.ItemSink sink) {
        List<RuntimeItemDO> turnItems = items.listBySession(ctx.threadId()).stream()
                .filter(i -> ctx.turnId().equals(i.getTurnId()))
                .filter(i -> i.getDiscarded() == null || !i.getDiscarded())
                .toList();
        Set<String> answeredCallIds = new HashSet<>();
        for (RuntimeItemDO i : turnItems) {
            if (ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                parseCallId(i).ifPresent(answeredCallIds::add);
            }
        }
        List<RuntimeItemDO> orphans = new ArrayList<>();
        for (RuntimeItemDO i : turnItems) {
            if (!ItemType.TOOL_CALL.wire().equals(i.getType())) {
                continue;
            }
            String callId = parseCallId(i).orElse(null);
            if (callId != null && !answeredCallIds.contains(callId)) {
                orphans.add(i);
            }
        }
        if (orphans.isEmpty()) {
            return;
        }
        int step = orphans.get(orphans.size() - 1).getStep() == null
                ? ctx.startStep() : orphans.get(orphans.size() - 1).getStep();

        List<LoopPorts.ToolCall> toInvoke = new ArrayList<>();
        List<String> approvedBy = new ArrayList<>();
        for (RuntimeItemDO callItem : orphans) {
            Optional<RuntimeApprovalDO> appr = approvals.findByItemId(callItem.getId());
            if (appr.isEmpty()) {
                continue;
            }
            RuntimeApprovalDO a = appr.get();
            ApprovalStatus st = ApprovalStatus.valueOf(a.getStatus());
            String callId = parseCallId(callItem).orElse("unknown");
            String name = a.getToolName() != null ? a.getToolName() : parseName(callItem).orElse("tool");
            switch (st) {
                case APPROVED -> {
                    Map<String, Object> args = resolveApprovedArgs(a);
                    toInvoke.add(new LoopPorts.ToolCall(callId, name, json(args)));
                    approvedBy.add(a.getDecidedBy());
                }
                case REJECTED -> {
                    String comment = extractComment(a.getAnswerJson());
                    sink.toolResult(ctx, step, new LoopPorts.ToolOutcome(
                            callId, name, false,
                            "用户拒绝执行：" + (comment == null ? "" : comment), 0));
                }
                case EXPIRED -> sink.toolResult(ctx, step, new LoopPorts.ToolOutcome(
                        callId, name, false, "审批超时，未执行", 0));
                case PENDING -> { /* 不应被派发 */ }
            }
        }
        if (!toInvoke.isEmpty()) {
            List<LoopPorts.ToolOutcome> outs = toolPort.invokeAll(ctx, toInvoke);
            for (int i = 0; i < outs.size(); i++) {
                LoopPorts.ToolOutcome o = outs.get(i);
                sink.toolResult(ctx, step, new LoopPorts.ToolOutcome(
                        o.callId(), o.name(), o.ok(), o.output(), o.durationMs(), approvedBy.get(i)));
            }
        }
    }

    public List<PendingToolCall> classify(LoopContext ctx, List<CallBinding> bindings) {
        Principal principal = principals.current();
        ApprovalPolicy policy = policies.resolve(ctx.approvalPolicy(), principal);
        List<PendingToolCall> out = new ArrayList<>();
        for (CallBinding b : bindings) {
            ToolProvider.ToolDescriptor desc = findDescriptor(ctx.agentVersionId(), b.call().name());
            Map<String, Object> args = parseMap(b.call().argumentsJson());
            ApprovalPolicy.ApprovalRequestContext req = ApprovalRequestContexts.from(
                    principal, ctx.agentVersionId(), b.call().name(), args, desc, Map.of());
            out.add(new PendingToolCall(b.itemId(), b.call(), args, desc, policy.evaluate(req)));
        }
        return out;
    }

    public void suspend(LoopContext ctx, List<PendingToolCall> requires) {
        suspendService.suspend(ctx, requires);
    }

    private ToolProvider.ToolDescriptor findDescriptor(String agentVersionId, String name) {
        return tools.listAll(agentVersionId).stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElse(new ToolProvider.ToolDescriptor(name, "", Map.of(), ToolRisk.WRITE));
    }

    private Map<String, Object> resolveApprovedArgs(RuntimeApprovalDO a) {
        Map<String, Object> answer = parseMap(a.getAnswerJson());
        Object edited = answer.get("args");
        if (edited instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return parseMap(a.getArgsJson());
    }

    private Optional<String> parseCallId(RuntimeItemDO item) {
        try {
            JsonNode n = mapper.readTree(item.getContentJson() == null ? "{}" : item.getContentJson());
            String id = n.path("callId").asText(null);
            return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> parseName(RuntimeItemDO item) {
        try {
            JsonNode n = mapper.readTree(item.getContentJson() == null ? "{}" : item.getContentJson());
            String name = n.path("name").asText(null);
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
        } catch (Exception e) {
            return Optional.empty();
        }
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

    private String extractComment(String answerJson) {
        Map<String, Object> m = parseMap(answerJson);
        Object c = m.get("comment");
        return c == null ? null : String.valueOf(c);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    public record CallBinding(String itemId, LoopPorts.ToolCall call) {}
}
