package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.ontology.application.query.CoverageService;
import com.tioka.ontology.application.query.EntityReadToolProvider;
import com.tioka.ontology.application.query.ModelCoverageToolProvider;
import com.tioka.ontology.application.query.ModelQueryService;
import com.tioka.ontology.application.query.ModelQueryToolProvider;
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
 * CoverageService。全部经 @Bean 条件装配（query 包内无 @Service/@Component）：
 * CoverageService 对 DefinitionService 弱依赖（ObjectProvider，未装配时期望项降级空集），
 * query.enabled=true 而 definition.enabled=false 可正常装配；
 * 仓储依赖为无条件 @Repository 组件（Mapper 经 framework 全局 @MapperScan 可用），无断点。
 * 数据集 ontology.instance 登记延后至管理面开放（registrar 已移除，见设计纪要偏差①）。
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
    CoverageService coverageService(ObjectProvider<com.tioka.ontology.application.def.DefinitionService> definitions,
                                    OntValueIndexRepository valueIndex,
                                    ObjectMapper mapper,
                                    ObjectProvider<RuleEvaluator> rules) {
        // DefinitionService 弱依赖（definition.enabled 缺省 false）；主题 CEL 适用条件源缺省为空
        // （M3-04 领域包复核时挂接真实来源，登记偏差）
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
}
