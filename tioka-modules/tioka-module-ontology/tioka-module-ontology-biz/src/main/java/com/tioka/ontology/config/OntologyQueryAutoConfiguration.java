package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.ontology.application.query.CoverageService;
import com.tioka.ontology.application.query.EntityReadToolProvider;
import com.tioka.ontology.application.query.ModelCoverageToolProvider;
import com.tioka.ontology.application.query.ModelQueryService;
import com.tioka.ontology.application.query.ModelQueryToolProvider;
import com.tioka.ontology.application.query.OntologyInstanceDatasetRegistrar;
import com.tioka.ontology.application.rule.RuleEvaluator;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * ONT-03 本体查询三工具装配。默认关闭：{@code tioka.ontology.query.enabled=true} 才注册。
 * 注册 {@code entity.read} / {@code model.query} / {@code model.coverage} 三 ToolProvider
 * （经 Spring Bean 进 runtime ToolRegistry，与 DocToolProvider 同路）与 ModelQueryService /
 * CoverageService；并向 DatasetStore best-effort 登记 ontology.instance 数据集（DAT-01 治理面）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.query", name = "enabled", havingValue = "true")
public class OntologyQueryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModelQueryService modelQueryService(OntInstanceVersionRepository versions, OntDraftRepository drafts) {
        return new ModelQueryService(versions, drafts);
    }

    @Bean
    @ConditionalOnMissingBean
    CoverageService coverageService(com.tioka.ontology.application.def.DefinitionService definitions,
                                    OntValueIndexRepository valueIndex,
                                    ObjectMapper mapper,
                                    ObjectProvider<RuleEvaluator> rules) {
        // 主题 CEL 适用条件源缺省为空（M3-04 领域包复核时挂接真实来源，登记偏差）
        return new CoverageService(definitions, valueIndex, mapper, rules, topic -> null);
    }

    @Bean
    @ConditionalOnMissingBean
    EntityReadToolProvider.PrincipalAccessor ontologyPrincipalAccessor(ObjectProvider<PrincipalHolder> holders) {
        return ctx -> {
            if (ctx != null && ctx.principal() != null) {
                return ctx.principal();
            }
            PrincipalHolder holder = holders.getIfAvailable();
            return holder == null ? null : holder.find().orElse(null);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    EntityReadToolProvider entityReadToolProvider(ModelQueryService queries,
                                                  ObjectMapper mapper,
                                                  EntityReadToolProvider.PrincipalAccessor principals) {
        return new EntityReadToolProvider(queries, mapper, principals);
    }

    @Bean
    @ConditionalOnMissingBean
    ModelQueryToolProvider modelQueryToolProvider(ModelQueryService queries,
                                                  OntValueIndexRepository valueIndex,
                                                  ObjectMapper mapper,
                                                  EntityReadToolProvider.PrincipalAccessor principals) {
        return new ModelQueryToolProvider(queries, valueIndex, mapper, principals);
    }

    @Bean
    @ConditionalOnMissingBean
    ModelCoverageToolProvider modelCoverageToolProvider(ModelQueryService queries,
                                                        CoverageService coverage,
                                                        ObjectMapper mapper,
                                                        EntityReadToolProvider.PrincipalAccessor principals) {
        return new ModelCoverageToolProvider(queries, coverage, mapper, principals);
    }

    @Bean(initMethod = "register")
    @ConditionalOnMissingBean
    OntologyInstanceDatasetRegistrar ontologyInstanceDatasetRegistrar(ObjectProvider<com.tioka.connector.application.dataset.DatasetStore> stores) {
        return new OntologyInstanceDatasetRegistrar(stores);
    }
}
