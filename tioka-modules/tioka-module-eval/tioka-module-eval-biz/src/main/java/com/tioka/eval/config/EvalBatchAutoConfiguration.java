package com.tioka.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.application.BatchEvalService;
import com.tioka.eval.application.DatasetService;
import com.tioka.eval.evaluator.EvaluatorRegistry;
import com.tioka.eval.infrastructure.EvalRunRepository;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.RuntimeApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * EVO-02：批次评测运行装配（M3-06 §4.2）。
 * 双开关纪律（C-8 清单）：主开关 {@code tioka.eval.enabled} 且子开关
 * {@code tioka.eval.batch.enabled}（缺省 false）同时为 true 才注册 {@link BatchEvalService}
 * ——异步执行器、turn 轮询与 EVAL_RUN_FINISHED 发布均为副作用面较大的组件，
 * 关闭态必须零注册、行为不变。
 */
@AutoConfiguration
@Conditional(EvalBatchAutoConfiguration.BatchSwitches.class)
public class EvalBatchAutoConfiguration {

    @Bean
    public BatchEvalService batchEvalService(DatasetService datasets,
                                             EvalRunRepository runs,
                                             EvaluatorRegistry registry,
                                             RuntimeApi runtime,
                                             PrincipalHolder principals,
                                             EventPublisher publisher,
                                             ObjectMapper mapper,
                                             PlatformTransactionManager transactionManager,
                                             @Value("${tioka.eval.batch.case-timeout-seconds:60}")
                                             long caseTimeoutSeconds) {
        return new BatchEvalService(datasets, runs, registry, runtime, principals,
                publisher, mapper, transactionManager, caseTimeoutSeconds);
    }

    static class BatchSwitches extends AllNestedConditions {
        BatchSwitches() {
            super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "tioka.eval", name = "enabled", havingValue = "true")
        static class MasterOn {}

        @ConditionalOnProperty(prefix = "tioka.eval.batch", name = "enabled", havingValue = "true")
        static class BatchOn {}
    }
}
