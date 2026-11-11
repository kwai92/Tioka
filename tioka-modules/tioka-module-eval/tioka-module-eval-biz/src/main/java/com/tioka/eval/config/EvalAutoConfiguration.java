package com.tioka.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.application.DatasetService;
import com.tioka.eval.application.EvalFeedbackService;
import com.tioka.eval.application.KnowledgeRecallEvalService;
import com.tioka.eval.evaluator.CitationEvaluator;
import com.tioka.eval.evaluator.ContainsEvaluator;
import com.tioka.eval.evaluator.EvaluatorRegistry;
import com.tioka.eval.evaluator.ExactEvaluator;
import com.tioka.eval.evaluator.LlmJudgeEvaluator;
import com.tioka.eval.evaluator.MrrEvaluator;
import com.tioka.eval.evaluator.RecallAt5Evaluator;
import com.tioka.eval.infrastructure.EvalDatasetRepository;
import com.tioka.eval.infrastructure.EvalFeedbackRepository;
import com.tioka.eval.infrastructure.EvalRunRepository;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import com.tioka.model.api.gateway.ModelGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 评测模块自动配置（P3-00b 空壳起步，EVO-01 落地反馈与轨迹导出，EVO-02 落地数据集与评估器 SPI，
 * C-16 落地召回评估器 recall_at_5/mrr 与召回执行器 KnowledgeRecallEvalService）。
 * 单开关 {@code tioka.eval.enabled}（缺省 false）条件装配；关闭时不注册任何 bean，
 * 不改变现有行为（C-8/C-12 收口纪律）。反馈/导出依赖的 TrajectoryApi / ThreadLabelApi
 * 为 runtime-biz 无条件核心 bean（模块 pom 保证同装配）；llm_judge 的 ModelGateway
 * 经 ObjectProvider 弱依赖。批次评测运行器见 {@link EvalBatchAutoConfiguration}
 * （双开关 tioka.eval.enabled + tioka.eval.batch.enabled）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.eval", name = "enabled", havingValue = "true")
@ComponentScan(basePackages = "com.tioka.eval")
public class EvalAutoConfiguration {

    @Bean
    public EvalFeedbackService evalFeedbackService(EvalFeedbackRepository repo, EventPublisher publisher) {
        return new EvalFeedbackService(repo, publisher);
    }

    @Bean
    public EvalFeedbackService.UndoFeedbackConsumer evalUndoFeedbackConsumer(EvalFeedbackService service) {
        return new EvalFeedbackService.UndoFeedbackConsumer(service);
    }

    @Bean
    public EvalFeedbackService.LeaseReviewFeedbackConsumer evalLeaseReviewFeedbackConsumer(
            EvalFeedbackService service, ObjectMapper mapper) {
        return new EvalFeedbackService.LeaseReviewFeedbackConsumer(service, mapper);
    }

    // ---- EVO-02：数据集 + 评估器 SPI ----

    @Bean
    public DatasetService evalDatasetService(EvalDatasetRepository repo, ObjectMapper mapper) {
        return new DatasetService(repo, mapper);
    }

    @Bean
    public ExactEvaluator exactEvaluator() {
        return new ExactEvaluator();
    }

    @Bean
    public ContainsEvaluator containsEvaluator() {
        return new ContainsEvaluator();
    }

    @Bean
    public CitationEvaluator citationEvaluator() {
        return new CitationEvaluator();
    }

    @Bean
    public LlmJudgeEvaluator llmJudgeEvaluator(ObjectProvider<ModelGateway> gateway,
                                               PrincipalHolder principals,
                                               ObjectMapper mapper) {
        return new LlmJudgeEvaluator(gateway, principals, mapper);
    }

    // ---- C-16（KNW-07）：召回评估器 + 召回执行器 ----

    @Bean
    public RecallAt5Evaluator recallAt5Evaluator() {
        return new RecallAt5Evaluator();
    }

    @Bean
    public MrrEvaluator mrrEvaluator() {
        return new MrrEvaluator();
    }

    /**
     * 召回评测执行器（C-16）：仅当知识检索模块在场（KnowledgeSearchApi bean 存在）时装配；
     * 缺席时不注册（运行期误用会在 startRecallRun 处抛 NOT_IMPLEMENTED 明确错误，
     * 由 ObjectProvider 弱依赖语义保证）。
     */
    @Bean
    @ConditionalOnBean(KnowledgeSearchApi.class)
    public KnowledgeRecallEvalService knowledgeRecallEvalService(DatasetService datasets,
                                                                 EvalRunRepository runs,
                                                                 EvaluatorRegistry registry,
                                                                 ObjectProvider<KnowledgeSearchApi> searchApi,
                                                                 PrincipalHolder principals,
                                                                 ObjectMapper mapper,
                                                                 PlatformTransactionManager transactionManager) {
        return new KnowledgeRecallEvalService(datasets, runs, registry, searchApi,
                principals, mapper, transactionManager);
    }

    /** SPI 注册表：收集容器内全部 Evaluator（内置六个 + 第三方领域包注册的 bean）。 */
    @Bean
    public EvaluatorRegistry evaluatorRegistry(java.util.List<com.tioka.eval.api.spi.Evaluator> evaluators) {
        return new EvaluatorRegistry(evaluators);
    }
}
