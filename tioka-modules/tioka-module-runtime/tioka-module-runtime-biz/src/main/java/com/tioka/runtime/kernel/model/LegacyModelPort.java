package com.tioka.runtime.kernel.model;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.LlmClientApi;
import com.tioka.model.api.ModelRouterApi;
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
 * M0：经 ModelRouter + LlmClient 调模型；取消时在 onDelta 抛 {@link CancelledSignal}。
 */
@Component
public class LegacyModelPort implements LoopPorts.ModelPort {
    private final ModelRouterApi router;
    private final LlmClientApi llm;
    private final SignalBus signals;

    public LegacyModelPort(ModelRouterApi router, LlmClientApi llm, SignalBus signals) {
        this.router = router;
        this.llm = llm;
        this.signals = signals;
    }

    @Override
    public Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                       Consumer<Delta> onDelta) {
        ModelRouterApi.ModelSelection sel = ctx.modelSelection();
        if (sel == null) {
            String planId = ctx.routingPlanId();
            if (planId == null || planId.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "thread 缺少 routingPlanId");
            }
            String firstUser = firstUserText(messages);
            sel = router.select(ctx.threadId(), ctx.turnId(), planId, firstUser);
            ctx.setModelSelection(sel);
        }
        try {
            LlmClientApi.CompletionResult r = llm.streamComplete(
                    sel.baseUrl(), sel.apiKeyRef(), sel.modelName(),
                    messages, tools == null ? List.of() : tools,
                    d -> {
                        if (signals.isCancelFlag(ctx.turnId())) {
                            throw new CancelledSignal();
                        }
                        if (d != null && d.textDelta() != null && !d.textDelta().isEmpty() && onDelta != null) {
                            onDelta.accept(new Delta(d.textDelta(), null));
                        }
                    });
            List<LoopPorts.ToolCall> calls = new ArrayList<>();
            if (r.toolCalls() != null) {
                for (LlmClientApi.ToolCall tc : r.toolCalls()) {
                    calls.add(new LoopPorts.ToolCall(tc.id(), tc.name(), tc.argumentsJson()));
                }
            }
            return new Result(r.content(), null, calls, r.promptTokens(), r.completionTokens(), sel.modelRef());
        } catch (CancelledSignal e) {
            throw e;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "模型调用失败: " + e.getMessage());
        }
    }

    private static String firstUserText(List<Map<String, Object>> messages) {
        if (messages == null) {
            return "";
        }
        for (Map<String, Object> m : messages) {
            if ("user".equals(String.valueOf(m.get("role")))) {
                Object c = m.get("content");
                return c == null ? "" : String.valueOf(c);
            }
        }
        return "";
    }
}
