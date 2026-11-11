package com.tioka.knowledge.config;

import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.knowledge.infrastructure.parse.DocParserAdapter;
import com.tioka.ontology.api.doc.DocumentApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * KNW-11：解析端口装配（条件装配纪律）。
 * 仅当 DOC 域 {@link DocumentApi}（tioka.ontology.doc.enabled）与对象交换层
 * {@link ObjectStorageApi} 均在位时注册 {@link DocParserAdapter}；任一缺席 →
 * 不注册 ParserPort，服务侧（ObjectProvider 弱依赖）降级 TextStructuredParser 本地结构化解析。
 * DocParserAdapter 不用 @Service/@Component 无条件注册。
 */
@AutoConfiguration(afterName = {
        "com.tioka.ontology.config.OntologyDocAutoConfiguration",
        "com.tioka.knowledge.config.KnowledgeAutoConfiguration"
})
public class KnowledgeParseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ParserPort.class)
    @ConditionalOnBean({DocumentApi.class, ObjectStorageApi.class})
    public ParserPort docParserAdapter(DocumentApi documents, ObjectStorageApi storage) {
        return new DocParserAdapter(documents, storage);
    }
}
