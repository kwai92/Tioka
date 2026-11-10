package com.tioka.runtime.kernel.model;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.runtime.kernel.domain.CancelledSignal;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.infrastructure.redis.SignalBus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MH-04：经 {@link ModelGateway} 调模型；取消时在 onDelta 抛 {@link CancelledSignal}。
 * MH-02：将 {@link ModelGateway.StreamDelta#discard()} 转为 {@link Delta#discard()}。
 */
@Component
public class GatewayModelPort implements LoopPorts.ModelPort {
    private final ModelGateway gateway;
    private final SignalBus signals;
    private final PrincipalHolder principals;

    public GatewayModelPort(ModelGateway gateway, SignalBus signals, PrincipalHolder principals) {
        this.gateway = gateway;
        this.signals = signals;
        this.principals = principals;
    }

    @Override
    public Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                       Consumer<Delta> onDelta) {
        return chat(ctx, messages, tools, onDelta, null);
    }

    @Override
    public Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                       Consumer<Delta> onDelta, com.tioka.model.api.constraint.StructuredDemand structured) {
        Principal principal = principals.find().orElse(SystemPrincipals.system());
        String planId = ctx.routingPlanId();
        ModelCallContext callCtx = new ModelCallContext(
                principal, ctx.threadId(), ctx.turnId(), null, planId, ModelPurpose.CHAT);
        try {
            ModelGateway.ChatResult r = gateway.chat(
                    callCtx,
                    new ModelGateway.ChatRequest(messages, tools == null ? List.of() : tools, null, null,
                            structured),
                    d -> {
                        if (signals.isCancelFlag(ctx.turnId())) {
                            throw new CancelledSignal();
                        }
                        if (d != null && d.discarded()) {
                            if (onDelta != null) {
                                onDelta.accept(Delta.discard());
                            }
                            return;
                        }
                        if (d != null && d.textDelta() != null && !d.textDelta().isEmpty()
                                && onDelta != null) {
                            onDelta.accept(new Delta(d.textDelta(), d.reasoningDelta()));
                        }
                    });
            List<LoopPorts.ToolCall> calls = new ArrayList<>();
            if (r.toolCalls() != null) {
                for (ModelGateway.ToolCall tc : r.toolCalls()) {
                    calls.add(new LoopPorts.ToolCall(tc.id(), tc.name(), tc.argumentsJson()));
                }
            }
            return new Result(r.content(), r.reasoning(), calls, r.promptTokens(), r.completionTokens(), r.modelRef());
        } catch (CancelledSignal e) {
            throw e;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "模型调用失败: " + e.getMessage());
        }
    }
}
