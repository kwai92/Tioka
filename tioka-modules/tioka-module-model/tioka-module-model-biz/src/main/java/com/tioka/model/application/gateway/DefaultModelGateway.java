package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.model.api.FailoverEvent;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.model.infrastructure.provider.ProviderHttpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MH-04 骨架 + MH-01 冻结 resolve + MH-02 自动故障接力。
 * 配额/限流/重试退避/记账由后续工作项填入。
 */
@Service
public class DefaultModelGateway implements ModelGateway {
    private final BindingResolver resolver;
    private final ProviderClient provider;
    private final ModelEntities entities;
    private final ApplicationEventPublisher events;
    private final int failoverMax;

    public DefaultModelGateway(BindingResolver resolver,
                               ProviderClient provider,
                               ModelEntities entities,
                               ApplicationEventPublisher events,
                               @Value("${tioka.model.failover-max:2}") int failoverMax) {
        this.resolver = resolver;
        this.provider = provider;
        this.entities = entities;
        this.events = events;
        this.failoverMax = Math.max(0, failoverMax);
    }

    @Override
    public ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta) {
        BindingResolver.Resolution sel = resolver.resolve(ctx);
        List<String> chain = new ArrayList<>(sel.fallbackChain() == null ? List.of() : sel.fallbackChain());
        int failovers = 0;
        AtomicBoolean emitted = new AtomicBoolean(false);
        Consumer<StreamDelta> sink = d -> {
            if (onDelta == null || d == null) {
                return;
            }
            if (!d.discarded() && d.textDelta() != null && !d.textDelta().isEmpty()) {
                emitted.set(true);
            }
            onDelta.accept(d);
        };

        while (true) {
            try {
                return invokeChat(sel, request, sink, failovers);
            } catch (BizException e) {
                throw e;
            } catch (ProviderHttpException e) {
                if (!e.switchable()) {
                    throw new BizException(ErrorCode.MODEL_ERROR, upstreamMessage(e));
                }
                if (failovers >= failoverMax || chain.isEmpty()) {
                    throw new BizException(ErrorCode.MODEL_UNAVAILABLE,
                            "故障接力链耗尽: " + upstreamMessage(e));
                }
                String nextId = chain.remove(0);
                String reason = "http-" + e.statusCode();
                failoverTo(ctx, sel, nextId, chain, reason, emitted, sink);
                failovers++;
                sel = resolver.ofBinding(nextId, chain, "failover");
            } catch (Exception e) {
                // 未分类异常：可切换（连接类已在 Provider 包装为 ProviderHttpException）
                if (failovers >= failoverMax || chain.isEmpty()) {
                    throw new BizException(ErrorCode.MODEL_UNAVAILABLE,
                            "故障接力链耗尽: " + e.getMessage());
                }
                String nextId = chain.remove(0);
                failoverTo(ctx, sel, nextId, chain, "error:" + e.getClass().getSimpleName(),
                        emitted, sink);
                failovers++;
                sel = resolver.ofBinding(nextId, chain, "failover");
            }
        }
    }

    private ChatResult invokeChat(BindingResolver.Resolution sel,
                                  ChatRequest request,
                                  Consumer<StreamDelta> sink,
                                  int failovers) throws Exception {
        Consumer<ProviderClient.StreamDelta> providerSink = d -> {
            if (sink != null && d != null && d.textDelta() != null && !d.textDelta().isEmpty()) {
                sink.accept(StreamDelta.text(d.textDelta()));
            }
        };
        ProviderClient.CompletionResult r = provider.streamComplete(
                sel.baseUrl(), sel.apiKeyRef(), sel.modelName(),
                request == null || request.messages() == null ? List.of() : request.messages(),
                request == null || request.tools() == null ? List.of() : request.tools(),
                providerSink);
        if ("content_filter".equalsIgnoreCase(r.finishReason())) {
            throw new BizException(ErrorCode.MODEL_ERROR, "内容安全拦截（content_filter）");
        }
        List<ToolCall> calls = new ArrayList<>();
        if (r.toolCalls() != null) {
            for (ProviderClient.ToolCall tc : r.toolCalls()) {
                calls.add(new ToolCall(tc.id(), tc.name(), tc.argumentsJson()));
            }
        }
        return new ChatResult(r.content(), null, calls, r.promptTokens(), r.completionTokens(),
                sel.bindingId(), sel.modelRef(), failovers, r.finishReason());
    }

    private void failoverTo(ModelCallContext ctx,
                            BindingResolver.Resolution from,
                            String nextBindingId,
                            List<String> remaining,
                            String reason,
                            AtomicBoolean emitted,
                            Consumer<StreamDelta> sink) {
        if (emitted.get() && sink != null) {
            sink.accept(StreamDelta.discard());
            emitted.set(false);
        }
        if (ctx.sessionId() != null && ctx.turnId() != null) {
            entities.recordFailover(ctx.sessionId(), ctx.turnId(), nextBindingId,
                    reason == null ? "failover" : reason);
        }
        BindingResolver.Resolution to = resolver.ofBinding(nextBindingId, remaining, "failover");
        events.publishEvent(new FailoverEvent(
                ctx.sessionId(), to.modelRef(),
                reason == null ? "failover" : reason,
                ctx.turnId(), from.modelRef()));
    }

    private static String upstreamMessage(ProviderHttpException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? ErrorCode.MODEL_ERROR.getMessage() : m;
    }

    @Override
    public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
        String forced = request == null ? null : request.bindingId();
        List<String> inputs = request == null || request.inputs() == null ? List.of() : request.inputs();
        try {
            BindingResolver.Resolution sel;
            try {
                sel = resolver.resolve(ctx, forced);
            } catch (BizException ex) {
                // MH-04 过渡：尚未配置嵌入 binding/默认方案时走本地 mock 哈希（KNW-01 收紧）
                if (ex.getErrorCode() != ErrorCode.BAD_REQUEST && ex.getErrorCode() != ErrorCode.NOT_FOUND) {
                    throw ex;
                }
                List<float[]> vectors = provider.embed("mock://openai", "", "hash-embed", null, inputs);
                int dim = vectors.isEmpty() ? 0 : vectors.get(0).length;
                int promptTokens = inputs.stream().mapToInt(s -> s == null ? 0 : Math.max(1, s.length() / 3)).sum();
                return new EmbeddingResult(vectors, dim, promptTokens, "bnd_mock_local");
            }
            List<float[]> vectors = provider.embed(
                    sel.baseUrl(), sel.apiKeyRef(), sel.modelName(),
                    sel.binding().embeddingDim(), inputs);
            int dim = vectors.isEmpty() ? (sel.binding().embeddingDim() == null ? 0 : sel.binding().embeddingDim())
                    : vectors.get(0).length;
            int promptTokens = inputs.stream().mapToInt(s -> s == null ? 0 : Math.max(1, s.length() / 3)).sum();
            return new EmbeddingResult(vectors, dim, promptTokens, sel.bindingId());
        } catch (BizException e) {
            throw e;
        } catch (ProviderHttpException e) {
            throw new BizException(e.switchable() ? ErrorCode.MODEL_UNAVAILABLE : ErrorCode.MODEL_ERROR,
                    upstreamMessage(e));
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "嵌入调用失败: " + e.getMessage());
        }
    }

    @Override
    public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
        throw NotImplemented.of("ModelGateway.rerank", "MH-04");
    }
}
