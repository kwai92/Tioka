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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MH-04 骨架 + MH-01 冻结 + MH-02 故障接力 + MH-03 限流/同 binding 重试退避/冷却 + MH-05 用量 + GOV-03 配额。
 */
@Service
public class DefaultModelGateway implements ModelGateway {
    private final ModelBindingResolver resolver;
    private final ProviderClient provider;
    private final ModelEntities entities;
    private final ApplicationEventPublisher events;
    private final UsageRecorder usage;
    private final QuotaGuard quota;
    private final ModelRateLimiter rateLimiter;
    private final ModelBindingCooldownService cooldown;
    private final RetryBackoff backoff;
    private final int failoverMax;

    public DefaultModelGateway(ModelBindingResolver resolver,
                               ProviderClient provider,
                               ModelEntities entities,
                               ApplicationEventPublisher events,
                               UsageRecorder usage,
                               QuotaGuard quota,
                               ModelRateLimiter rateLimiter,
                               ModelBindingCooldownService cooldown,
                               @Value("${tioka.model.failover-max:2}") int failoverMax,
                               @Value("${tioka.model.retry.max-attempts:2}") int retryMaxAttempts,
                               @Value("${tioka.model.retry.base-delay:500ms}") Duration retryBaseDelay,
                               @Value("${tioka.model.retry.budget:20s}") Duration retryBudget) {
        this.resolver = resolver;
        this.provider = provider;
        this.entities = entities;
        this.events = events;
        this.usage = usage;
        this.quota = quota;
        this.rateLimiter = rateLimiter;
        this.cooldown = cooldown;
        this.failoverMax = Math.max(0, failoverMax);
        this.backoff = new RetryBackoff(retryMaxAttempts, retryBaseDelay, retryBudget);
    }

    @Override
    public ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta) {
        precheck(ctx);
        ModelBindingResolver.Resolution sel = resolver.resolve(ctx);
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
                ChatResult result = invokeChatWithRetry(sel, request, sink, failovers, emitted);
                account(ctx, sel, result.promptTokens(), result.completionTokens(), elapsedMs(t0), "SUCCESS");
                return result;
            } catch (BizException e) {
                if (e.getErrorCode() != ErrorCode.RATE_LIMITED) {
                    account(ctx, sel, 0, 0, elapsedMs(t0), "ERROR");
                }
                throw e;
            } catch (ProviderHttpException e) {
                int latency = elapsedMs(t0);
                cooldown.recordFailure(sel.bindingId());
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
                cooldown.recordFailure(sel.bindingId());
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

    /**
     * MH-03：限流 → 同 binding 重试（可切换错误）；耗尽后把最后一次异常抛给外层做 failover。
     */
    private ChatResult invokeChatWithRetry(ModelBindingResolver.Resolution sel,
                                           ChatRequest request,
                                           Consumer<StreamDelta> sink,
                                           int failovers,
                                           AtomicBoolean emitted) throws Exception {
        long reserved = acquireRateLimit(sel, request);
        long spentBackoff = 0L;
        int attempt = 0;
        while (true) {
            try {
                ChatResult result = invokeChat(sel, request, sink, failovers);
                reconcileTpm(sel, reserved, result.promptTokens() + result.completionTokens());
                return result;
            } catch (ProviderHttpException e) {
                if (!e.switchable() || attempt >= backoff.maxAttempts()) {
                    throw e;
                }
                long delay = backoff.delayMs(attempt, e.retryAfterMs(), spentBackoff);
                if (delay < 0) {
                    throw e;
                }
                cooldown.recordFailure(sel.bindingId());
                if (emitted.get() && sink != null) {
                    sink.accept(StreamDelta.discard());
                    emitted.set(false);
                }
                sleepQuiet(delay);
                spentBackoff += delay;
                attempt++;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= backoff.maxAttempts()) {
                    throw e;
                }
                long delay = backoff.delayMs(attempt, null, spentBackoff);
                if (delay < 0) {
                    throw e;
                }
                cooldown.recordFailure(sel.bindingId());
                if (emitted.get() && sink != null) {
                    sink.accept(StreamDelta.discard());
                    emitted.set(false);
                }
                sleepQuiet(delay);
                spentBackoff += delay;
                attempt++;
            }
        }
    }

    private long acquireRateLimit(ModelBindingResolver.Resolution sel, ChatRequest request) {
        if (sel == null || sel.binding() == null) {
            return 0L;
        }
        long est = estimateTokens(request);
        return rateLimiter.acquire(sel.bindingId(),
                sel.binding().rpmLimit(), sel.binding().tpmLimit(), est);
    }

    private void reconcileTpm(ModelBindingResolver.Resolution sel, long reserved, long actual) {
        if (sel == null || sel.binding() == null || reserved <= 0) {
            return;
        }
        rateLimiter.reconcileTpm(sel.bindingId(), sel.binding().tpmLimit(), reserved, actual);
    }

    static long estimateTokens(ChatRequest request) {
        int chars = 0;
        if (request != null && request.messages() != null) {
            for (Map<String, Object> m : request.messages()) {
                if (m == null) {
                    continue;
                }
                Object c = m.get("content");
                if (c != null) {
                    chars += String.valueOf(c).length();
                }
            }
        }
        int maxTokens = request == null || request.maxTokens() == null ? 0 : Math.max(0, request.maxTokens());
        return Math.max(1L, chars / 3L + maxTokens);
    }

    private ChatResult invokeChat(ModelBindingResolver.Resolution sel,
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
                            ModelBindingResolver.Resolution from,
                            String nextBindingId,
                            List<String> remaining,
                            String reason,
                            AtomicBoolean emitted,
                            Consumer<StreamDelta> sink) {
        if (emitted.get() && sink != null) {
            sink.accept(StreamDelta.discard());
            emitted.set(false);
        }
        if (ctx.threadId() != null && ctx.turnId() != null) {
            entities.recordFailover(ctx.threadId(), ctx.turnId(), nextBindingId,
                    reason == null ? "failover" : reason);
        }
        ModelBindingResolver.Resolution to = resolver.ofBinding(nextBindingId, remaining, "failover");
        events.publishEvent(new FailoverEvent(
                ctx.threadId(), to.modelRef(),
                reason == null ? "failover" : reason,
                ctx.turnId(), from.modelRef()));
    }

    private void account(ModelCallContext ctx, ModelBindingResolver.Resolution sel,
                         int promptTokens, int completionTokens, int latencyMs, String status) {
        long in = sel.binding() == null ? 0L : sel.binding().inputPriceMicros();
        long out = sel.binding() == null ? 0L : sel.binding().outputPriceMicros();
        usage.record(new UsageRecordCommand(ctx, sel.bindingId(), sel.modelRef(),
                promptTokens, completionTokens, in, out, latencyMs, status));
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

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(Math.max(1L, ms));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.MODEL_UNAVAILABLE, "重试等待被中断");
        }
    }

    @Override
    public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
        precheck(ctx);
        String forced = request == null ? null : request.bindingId();
        List<String> inputs = request == null || request.inputs() == null ? List.of() : request.inputs();
        long t0 = System.nanoTime();
        ModelBindingResolver.Resolution sel = null;
        try {
            // KNW-01：不再回退本地哈希；必须经 ModelBindingResolver 选到真实/mock Provider
            sel = resolver.resolve(ctx, forced);
            long est = Math.max(1L, inputs.stream().mapToInt(s -> s == null ? 0 : s.length()).sum() / 3L);
            long reserved = rateLimiter.acquire(sel.bindingId(),
                    sel.binding().rpmLimit(), sel.binding().tpmLimit(), est);
            List<float[]> vectors = provider.embed(
                    sel.baseUrl(), sel.apiKeyRef(), sel.modelName(),
                    sel.binding().embeddingDim(), inputs);
            int dim = vectors.isEmpty() ? (sel.binding().embeddingDim() == null ? 0 : sel.binding().embeddingDim())
                    : vectors.get(0).length;
            int promptTokens = inputs.stream().mapToInt(s -> s == null ? 0 : Math.max(1, s.length() / 3)).sum();
            rateLimiter.reconcileTpm(sel.bindingId(), sel.binding().tpmLimit(), reserved, promptTokens);
            account(ctx, sel, promptTokens, 0, elapsedMs(t0), "SUCCESS");
            return new EmbeddingResult(vectors, dim, promptTokens, sel.bindingId());
        } catch (BizException e) {
            accountEmbedError(ctx, sel, forced, elapsedMs(t0));
            throw e;
        } catch (ProviderHttpException e) {
            if (sel != null) {
                cooldown.recordFailure(sel.bindingId());
            }
            accountEmbedError(ctx, sel, forced, elapsedMs(t0));
            throw new BizException(e.switchable() ? ErrorCode.MODEL_UNAVAILABLE : ErrorCode.MODEL_ERROR,
                    upstreamMessage(e));
        } catch (Exception e) {
            if (sel != null) {
                cooldown.recordFailure(sel.bindingId());
            }
            accountEmbedError(ctx, sel, forced, elapsedMs(t0));
            throw new BizException(ErrorCode.DEPENDENCY, "嵌入调用失败: " + e.getMessage());
        }
    }

    private void accountEmbedError(ModelCallContext ctx, ModelBindingResolver.Resolution sel,
                                   String forced, int latencyMs) {
        if (sel != null) {
            account(ctx, sel, 0, 0, latencyMs, "ERROR");
        } else {
            usage.record(new UsageRecordCommand(ctx, forced, forced, 0, 0, 0L, 0L, latencyMs, "ERROR"));
        }
    }

    @Override
    public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
        throw NotImplemented.of("ModelGateway.rerank", "MH-04");
    }
}
