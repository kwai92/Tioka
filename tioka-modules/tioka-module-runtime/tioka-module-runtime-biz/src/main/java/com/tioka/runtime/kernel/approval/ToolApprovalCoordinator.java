package com.tioka.runtime.kernel.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.approval.ApprovalPolicy;
import com.tioka.runtime.api.approval.ApprovalPolicy.Assignee;
import com.tioka.runtime.api.approval.ApprovalPolicy.RequireApproval;
import com.tioka.runtime.api.approval.ApprovalRequestContexts;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.kernel.ApprovalStatus;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.pipeline.PolicyEvalPipelineStep;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    private static final Duration CHARTER_APPROVAL_TTL = Duration.ofHours(24);

    private final ApprovalPolicyResolver policies;
    private final ToolRegistry tools;
    private final PrincipalHolder principals;
    private final ApprovalRepository approvals;
    private final ItemRepository items;
    private final ApprovalSuspendService suspendService;
    private final ObjectMapper mapper;
    private final ObjectProvider<PolicyEvalPipelineStep> policyStep;

    public ToolApprovalCoordinator(ApprovalPolicyResolver policies,
                                   ToolRegistry tools,
                                   PrincipalHolder principals,
                                   ApprovalRepository approvals,
                                   ItemRepository items,
                                   ApprovalSuspendService suspendService,
                                   ObjectMapper mapper,
                                   ObjectProvider<PolicyEvalPipelineStep> policyStep) {
        this.policies = policies;
        this.tools = tools;
        this.principals = principals;
        this.approvals = approvals;
        this.items = items;
        this.suspendService = suspendService;
        this.mapper = mapper;
        this.policyStep = policyStep;
    }

    /**
     * 续跑：对无 tool_result 的 tool_call，按审批状态写结果并（批准时）真正调工具。
     */
    public void resumeOrphans(LoopContext ctx, LoopPorts.ToolPort toolPort, LoopPorts.ItemSink sink) {
        List<RuntimeItemDO> turnItems = items.listByThread(ctx.threadId()).stream()
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
            ToolProvider.ToolDescriptor desc = findDescriptor(ctx, b.call().name());
            Map<String, Object> args = parseMap(b.call().argumentsJson());
            ApprovalPolicy.ApprovalRequestContext req = ApprovalRequestContexts.from(
                    principal, ctx.agentVersionId(), b.call().name(), args, desc, Map.of());
            ApprovalPolicy.Decision decision = charterOverride(ctx, b.call().name(), args, policy.evaluate(req));
            out.add(new PendingToolCall(b.itemId(), b.call(), args, desc, decision));
        }
        return out;
    }

    /**
     * TRU-02 授权书 Cedar 求值与 agent-config 决定合并（M3-05 第 9 步，只收紧不放松）：
     * <ul>
     *   <li>ESCALATE → {@link RequireApproval}（reason 前缀 {@code fourEyes:} 驱动四眼标记，
     *       审批卡片 / 审批人 JSON 由 {@link ApprovalSuspendService} 落库）</li>
     *   <li>DENY（{@code POLICY_DENIED}）→ 保持 agent-config 决定放行到网关
     *       {@code call} 路径统一拒绝并写 {@code tool.invoke} DENIED 审计（拒绝结果回喂模型）</li>
     *   <li>其余（ALLOW / DRYRUN 命中）→ 维持 agent-config 决定</li>
     * </ul>
     */
    private ApprovalPolicy.Decision charterOverride(LoopContext ctx, String name,
                                                    Map<String, Object> args,
                                                    ApprovalPolicy.Decision current) {
        PolicyEvalPipelineStep step = policyStep.getIfAvailable();
        if (step == null || current instanceof ApprovalPolicy.Deny) {
            return current;
        }
        try {
            ToolGateway.ToolCallContext callCtx = ToolCallContexts.forTurn(
                    principals.current(), ctx.threadId(), ctx.turnId(), ctx.agentVersionId(),
                    ctx.origin(), ctx.toolAllowList(), ctx.agentToolConfig(), ctx.grantId());
            ToolPipelineContext pipeCtx = new ToolPipelineContext(callCtx, name, args);
            ToolPipelineVerdict verdict = step.execute(pipeCtx);
            if (verdict == ToolPipelineVerdict.ESCALATE) {
                Boolean fourEyes = pipeCtx.get("policy.fourEyes", Boolean.class);
                String actionClass = pipeCtx.get("policy.actionClass", String.class);
                return new RequireApproval(new Assignee(null, null), CHARTER_APPROVAL_TTL,
                        (Boolean.TRUE.equals(fourEyes) ? "fourEyes:" : "pipeline:")
                                + "charter:" + (actionClass == null ? "" : actionClass));
            }
        } catch (BizException e) {
            // POLICY_DENIED：交由网关 call 路径拒绝并审计
        }
        return current;
    }

    public void suspend(LoopContext ctx, List<PendingToolCall> requires) {
        suspendService.suspend(ctx, requires);
    }

    /** 必须带 agentToolConfig，否则 MCP 工具查不到会默认 WRITE 误挂起审批。 */
    private ToolProvider.ToolDescriptor findDescriptor(LoopContext ctx, String name) {
        return tools.listAll(ctx.agentVersionId(), ctx.agentToolConfig()).stream()
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
