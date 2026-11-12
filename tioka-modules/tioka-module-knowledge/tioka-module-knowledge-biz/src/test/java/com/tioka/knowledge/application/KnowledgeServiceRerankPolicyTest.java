package com.tioka.knowledge.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.embedding.HashEmbeddingClient;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KNW-04 / ADR-017：rerank 失败只能对「模型依赖不可用」类错误退回 RRF。
 * 配额、预算、参数、鉴权、策略类治理错误必须冒泡，不能被可用性优先静默绕过。
 */
class KnowledgeServiceRerankPolicyTest {

    @Test
    void governanceErrorsAreNotSwallowedByRerank() {
        // DEPENDENCY 是 DefaultModelGateway#rerank 兜底 catch 的包装码，也必须冒泡，
        // 否则任意未知异常会被包成 DEPENDENCY 后静默退回 RRF（fresh-eyes B-5）。
        for (ErrorCode code : List.of(ErrorCode.QUOTA_EXCEEDED,
                ErrorCode.MODEL_BUDGET_EXHAUSTED, ErrorCode.BAD_REQUEST,
                ErrorCode.FORBIDDEN, ErrorCode.POLICY_DENIED, ErrorCode.DEPENDENCY)) {
            Fixture f = fixture(new BizException(code, "blocked"));
            BizException ex = assertThrows(BizException.class,
                    () -> f.service().retrieveDetailed(List.of(f.kbId()), "差旅报销 流程", 5, null),
                    "错误码 " + code + " 必须冒泡，不能退化成「检索成功但绕过治理」");
            assertEquals(code, ex.getErrorCode());
        }
    }

    @Test
    void modelDependencyFailureFallsBackToRrf() {
        for (ErrorCode code : List.of(ErrorCode.MODEL_UNAVAILABLE,
                ErrorCode.MODEL_ERROR)) {
            Fixture f = fixture(new BizException(code, "down"));
            KnowledgeService.RetrievalOutcome outcome = assertDoesNotThrow(
                    () -> f.service().retrieveDetailed(List.of(f.kbId()), "差旅报销 流程", 5, null));
            assertFalse(outcome.reranked(), "依赖失败不计为 reranked");
            assertFalse(outcome.hits().isEmpty(), "须退回 RRF 结果，不能返回空集");
            assertEquals(KnowledgeService.NoAnswerReason.NONE, outcome.reason());
            assertEquals(0, outcome.rerankDropped(), "依赖失败不得按阈值丢弃候选");
        }
    }

    private record Fixture(KnowledgeService service, String kbId) {}

    private static Fixture fixture(BizException failure) {
        KnowledgeRepository repo = KnowledgeInMemorySupport.repo();
        KnowledgeService service = KnowledgeInMemorySupport.service(repo, failingRerankGateway(failure));
        var kb = service.createBase("ws", "rr-" + failure.getErrorCode(), null);
        service.configureRerank(kb.id(), "bnd_rr", 0.3d);
        service.ingest(kb.id(), "制度",
                "差旅报销流程与单据要求，门禁卡办理与补办规定。", "upload");
        return new Fixture(service, kb.id());
    }

    private static ModelGateway failingRerankGateway(BizException failure) {
        HashEmbeddingClient hash = new HashEmbeddingClient(32);
        return new ModelGateway() {
            @Override
            public ChatResult chat(ModelCallContext ctx, ChatRequest request,
                                   Consumer<StreamDelta> onDelta) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.tioka.model.api.capability.OcrResult ocr(
                    ModelCallContext ctx, com.tioka.model.api.capability.OcrRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
                List<String> inputs = request.inputs() == null ? List.of() : request.inputs();
                List<float[]> vectors = new ArrayList<>();
                for (String text : inputs) {
                    double[] d = hash.embed(text);
                    float[] f = new float[d.length];
                    for (int i = 0; i < d.length; i++) {
                        f[i] = (float) d[i];
                    }
                    vectors.add(f);
                }
                return new EmbeddingResult(vectors, hash.dimension(), inputs.size(), "bnd_hash");
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                throw failure;
            }
        };
    }
}
