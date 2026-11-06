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
import com.tioka.system.api.spi.QuotaGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MH-04 骨架 + MH-01 冻结 resolve + MH-02 故障接力 + MH-05 用量记账 + GOV-03 配额。
 * 限流/重试退避由 MH-03 填入。
 */
@Service
public class DefaultModelGateway implements ModelGateway {
    private final BindingResolver resolver;
    private final ProviderClient provider;
    private final ModelEntities entities;
    private final ApplicationEventPublisher events;
    private final UsageRecorder usage;
    private final QuotaGuard quota;
    private final int failoverMax;

    public DefaultModelGateway(BindingResolver resolver,
                               ProviderClient provider,
                               ModelEntities entities,
                               ApplicationEventPublisher events,
                               UsageRecorder usage,
                               QuotaGuard quota,
                               @Value("${tioka.model.failover-max:2}") int failoverMax) {
        this.resolver = resolver;
        this.provider = provider;
        this.entities = entities;
        this.events = events;
        this.usage = usage;
        this.quota = quota;
        this.failoverMax = Math.max(0, failoverMax);
    }

    @Override
    public ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta) {
        precheck(ctx);
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
            long t0 = System.nanoTime();
            try {
                ChatResult result = invokeChat(sel, request, sink, failovers);
                account(ctx, sel, result.promptTokens(), result.completionTokens(), elapsedMs(t0), "SUCCESS");
                return result;
            } catch (BizException e) {
                account(ctx, sel, 0, 0, elapsedMs(t0), "ERROR");
                throw e;
            } catch (ProviderHttpException e) {
                int latency = elapsedMs(t0);
                if (!e.switchable()) {
                    account(ctx, sel, 0, 0, latency, "ERROR");
                    throw new BizException(ErrorCode.MODEL_ERROR, upstreamMessage(e));
                }
                if (failovers >= failoverMax || chain.isEmpty()) {
                    account(ctx, sel, 0, 0, latency, "ERROR");
                    throw new BizException(ErrorCode.MODEL_UNAVAILABLE,
                            "故障接力链耗尽: " + upstreamMessage(e));
                }
                account(ctx, sel, 0, 0, latency, "FAILOVER");
                String nextId = chain.remove(0);
                String reason = "http-" + e.statusCode();
                failoverTo(ctx, sel, nextId, chain, reason, emitted, sink);
                failovers++;
                sel = resolver.ofBinding(nextId, chain, "failover");
            } catch (Exception e) {
                int latency = elapsedMs(t0);
                if (failovers >= failoverMax || chain.isEmpty()) {
                    account(ctx, sel, 0, 0, latency, "ERROR");
                    throw new BizException(ErrorCode.MODEL_UNAVAILABLE,
                            "故障接力链耗尽: " + e.getMessage());
                }
                account(ctx, sel, 0, 0, latency, "FAILOVER");
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

    private void account(ModelCallContext ctx, BindingResolver.Resolution sel,
                         int promptTokens, int completionTokens, int latencyMs, String status) {
        long in = sel.binding() == null ? 0L : sel.binding().inputPriceMicros();
        long out = sel.binding() == null ? 0L : sel.binding().outputPriceMicros();
        usage.record(ctx, sel.bindingId(), sel.modelRef(),
                promptTokens, completionTokens, in, out, latencyMs, status);
        consume(ctx, promptTokens, completionTokens);
    }

    private void precheck(ModelCallContext ctx) {
        if (ctx == null || ctx.principal() == null) {
            return;
        }
        quota.precheckTokens(ctx.principal().workspaceId(), ctx.principal().userId(), ctx.agentId());
    }

    private void consume(ModelCallContext ctx, int promptTokens, int completionTokens) {
        long tokens = (long) Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (tokens <= 0 || ctx == null || ctx.principal() == null) {
            return;
        }
        quota.consumeTokens(ctx.principal().workspaceId(), ctx.principal().userId(), ctx.agentId(), tokens);
    }

    private static int elapsedMs(long t0Nanos) {
        return (int) Math.max(0L, (System.nanoTime() - t0Nanos) / 1_000_000L);
    }

    private static String upstreamMessage(ProviderHttpException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? ErrorCode.MODEL_ERROR.getMessage() : m;
    }

    @Override
    public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
        precheck(ctx);
        String forced = request == null ? null : request.bindingId();
        List<String> inputs = request == null || request.inputs() == null ? List.of() : request.inputs();
        long t0 = System.nanoTime();
        BindingResolver.Resolution sel = null;
        try {
            try {
                sel = resolver.resolve(ctx, forced);
            } catch (BizException ex) {
                // MH-04 过渡：尚未配置嵌入 binding/默认方案时走本地 mock 哈希（KNW-01 收紧）
                if (ex.getErrorCode() != ErrorCode.BAD_REQUEST && ex.getErrorCode() != ErrorCode.NOT_FOUND) {
                    usage.record(ctx, forced, forced, 0, 0, 0L, 0L, elapsedMs(t0), "ERROR");
                    throw ex;
                }
                List<float[]> vectors = provider.embed("mock://openai", "", "hash-embed", null, inputs);
                int dim = vectors.isEmpty() ? 0 : vectors.get(0).length;
                int promptTokens = inputs.stream().mapToInt(s -> s == null ? 0 : Math.max(1, s.length() / 3)).sum();
                usage.record(ctx, "bnd_mock_local", "hash-embed@mock",
                        promptTokens, 0, 0L, 0L, elapsedMs(t0), "SUCCESS");
                consume(ctx, promptTokens, 0);
                return new EmbeddingResult(vectors, dim, promptTokens, "bnd_mock_local");
            }
            List<float[]> vectors = provider.embed(
                    sel.baseUrl(), sel.apiKeyRef(), sel.modelName(),
                    sel.binding().embeddingDim(), inputs);
            int dim = vectors.isEmpty() ? (sel.binding().embeddingDim() == null ? 0 : sel.binding().embeddingDim())
                    : vectors.get(0).length;
            int promptTokens = inputs.stream().mapToInt(s -> s == null ? 0 : Math.max(1, s.length() / 3)).sum();
            account(ctx, sel, promptTokens, 0, elapsedMs(t0), "SUCCESS");
            return new EmbeddingResult(vectors, dim, promptTokens, sel.bindingId());
        } catch (BizException e) {
            throw e;
        } catch (ProviderHttpException e) {
            accountEmbedError(ctx, sel, forced, elapsedMs(t0));
            throw new BizException(e.switchable() ? ErrorCode.MODEL_UNAVAILABLE : ErrorCode.MODEL_ERROR,
                    upstreamMessage(e));
        } catch (Exception e) {
            accountEmbedError(ctx, sel, forced, elapsedMs(t0));
            throw new BizException(ErrorCode.DEPENDENCY, "嵌入调用失败: " + e.getMessage());
        }
    }

    private void accountEmbedError(ModelCallContext ctx, BindingResolver.Resolution sel,
                                   String forced, int latencyMs) {
        if (sel != null) {
            account(ctx, sel, 0, 0, latencyMs, "ERROR");
        } else {
            usage.record(ctx, forced, forced, 0, 0, 0L, 0L, latencyMs, "ERROR");
        }
    }

    @Override
    public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
        throw NotImplemented.of("ModelGateway.rerank", "MH-04");
    }
}
