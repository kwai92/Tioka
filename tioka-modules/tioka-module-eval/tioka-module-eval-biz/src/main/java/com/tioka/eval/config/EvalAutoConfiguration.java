package com.tioka.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.eval.application.EvalFeedbackService;
import com.tioka.eval.infrastructure.EvalFeedbackRepository;
import com.tioka.framework.messaging.EventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * 评测模块自动配置（P3-00b 空壳起步，EVO-01 落地反馈与轨迹导出）。
 * 单开关 {@code tioka.eval.enabled}（缺省 false）条件装配；关闭时不注册任何 bean，
 * 不改变现有行为（C-8/C-12 收口纪律）。反馈/导出依赖的 TrajectoryApi / ThreadLabelApi
 * 为 runtime-biz 无条件核心 bean（模块 pom 保证同装配）。
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
}
