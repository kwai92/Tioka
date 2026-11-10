package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.ontology.api.doc.DocumentApi;
import com.tioka.ontology.application.doc.DefaultParserRouter;
import com.tioka.ontology.application.doc.DocToolProvider;
import com.tioka.ontology.application.doc.ModelGatewayOcrClient;
import com.tioka.ontology.application.doc.OcrClient;
import com.tioka.ontology.application.doc.ParserRouter;
import com.tioka.ontology.application.doc.DocumentService;
import com.tioka.ontology.application.schema.EvidenceBinder;
import com.tioka.ontology.infrastructure.DocumentRepository;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 文档层装配（DOC-01〜03）。默认关闭：{@code tioka.ontology.doc.enabled=true} 才注册；
 * 仓储为进程内实现（MyBatis 持久化随 SCH 持久化批次接线，登记偏差）；
 * OCR 经 ModelGateway.ocr（未装配 ModelGateway 时解析队列任务标 FAILED ocr_unavailable）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.doc", name = "enabled", havingValue = "true")
public class OntologyDocAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DocumentRepository.class)
    DocumentRepository documentRepository() {
        return new InMemoryDocumentRepository();
    }

    @Bean
    @ConditionalOnMissingBean(OcrClient.class)
    OcrClient ocrClient(ObjectProvider<ModelGateway> gateway,
                        com.tioka.framework.security.PrincipalHolder principals) {
        ModelGateway gw = gateway.getIfAvailable();
        // gateway 缺失时 OcrClient 调用抛 ocr_unavailable（解析队列标 FAILED）
        if (gw == null) {
            return (pageNo, png, dataLabel, spaceId) -> {
                throw new IllegalStateException("ocr_unavailable: ModelGateway 未装配");
            };
        }
        return new ModelGatewayOcrClient(gw, principals);
    }

    @Bean
    ParserRouter parserRouter(com.tioka.ontology.application.doc.DocxParser docxParser,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${tioka.ontology.doc.soffice:soffice}") String soffice,
                              OcrClient ocrClient) {
        return new DefaultParserRouter(docxParser, soffice, ocrClient);
    }

    @Bean
    com.tioka.ontology.application.doc.DocxParser docxParser() {
        return new com.tioka.ontology.application.doc.DocxParser();
    }

    @Bean
    DocumentApi documentService(DocumentRepository repository, ParserRouter router,
                                ObjectStorageApi storage, ObjectProvider<com.tioka.framework.messaging.EventPublisher> publisher) {
        return new DocumentService(repository, router, storage, publisher.getIfAvailable());
    }

    @Bean
    EvidenceBinder evidenceBinder(DocumentRepository repository) {
        return new EvidenceBinder(repository);
    }

    @Bean
    DocToolProvider docToolProvider(DocumentApi documents, ObjectMapper mapper) {
        return new DocToolProvider(documents, mapper);
    }
}
