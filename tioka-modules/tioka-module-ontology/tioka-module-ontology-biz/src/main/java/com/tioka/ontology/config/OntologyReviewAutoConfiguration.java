package com.tioka.ontology.config;

import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.review.ReviewRoundService;
import com.tioka.ontology.infrastructure.LeaseReviewRoundRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * LSE-06 审查轮次装配。双开关：
 * {@code tioka.ontology.review.enabled=true} 且 {@code tioka.ontology.definition.enabled=true}
 * （轮次判断依赖领域包要点库 requires 配置，无定义装配则无从取要点）才注册。
 * 注册 ReviewRoundService + LeaseReviewRoundRepository（V175 lease_review_round）。
 */
@AutoConfiguration(after = OntologyDefinitionAutoConfiguration.class)
@ConditionalOnProperty(prefix = "tioka.ontology.review", name = "enabled", havingValue = "true")
@ConditionalOnBean(DefinitionService.class)
@MapperScan("com.tioka.ontology.infrastructure.mapper")
public class OntologyReviewAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LeaseReviewRoundRepository leaseReviewRoundRepository(
            com.tioka.ontology.infrastructure.mapper.LeaseReviewRoundMapper mapper) {
        return new LeaseReviewRoundRepository(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ReviewRoundService reviewRoundService(LeaseReviewRoundRepository rounds) {
        return new ReviewRoundService(rounds);
    }
}
