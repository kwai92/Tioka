package com.tioka.model.application.gateway;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.error.NotImplemented;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.infrastructure.provider.ProviderClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MH-04 骨架：第 1 步 resolve + 第 5 步 invoke。配额/限流/故障接力/记账由后续工作项填入。
 */
@Service
public class DefaultModelGateway implements ModelGateway {
    private final BindingResolver resolver;
    private final ProviderClient provider;

    public DefaultModelGateway(BindingResolver resolver, ProviderClient provider) {
        this.resolver = resolver;
        this.provider = provider;
    }

    @Override
    public ChatResult chat(ModelCallContext ctx, ChatRequest request, Consumer<StreamDelta> onDelta) {
        BindingResolver.Resolution sel = resolver.resolve(ctx);
        try {
            Consumer<ProviderClient.StreamDelta> sink = d -> {
                if (onDelta != null && d != null && d.textDelta() != null && !d.textDelta().isEmpty()) {
                    onDelta.accept(StreamDelta.text(d.textDelta()));
                }
            };
            ProviderClient.CompletionResult r = provider.streamComplete(
                    sel.baseUrl(), sel.apiKeyRef(), sel.modelName(),
                    request == null || request.messages() == null ? List.of() : request.messages(),
                    request == null || request.tools() == null ? List.of() : request.tools(),
                    sink);
            List<ToolCall> calls = new ArrayList<>();
            if (r.toolCalls() != null) {
                for (ProviderClient.ToolCall tc : r.toolCalls()) {
                    calls.add(new ToolCall(tc.id(), tc.name(), tc.argumentsJson()));
                }
            }
            return new ChatResult(r.content(), null, calls, r.promptTokens(), r.completionTokens(),
                    sel.bindingId(), sel.modelRef(), 0, r.finishReason());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "模型调用失败: " + e.getMessage());
        }
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
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEPENDENCY, "嵌入调用失败: " + e.getMessage());
        }
    }

    @Override
    public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
        throw NotImplemented.of("ModelGateway.rerank", "MH-04");
    }
}
