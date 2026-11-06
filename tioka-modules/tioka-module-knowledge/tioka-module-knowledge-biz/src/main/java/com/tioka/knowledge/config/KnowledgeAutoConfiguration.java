package com.tioka.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.knowledge.infrastructure.embedding.EmbeddingClient;
import com.tioka.knowledge.infrastructure.embedding.HashEmbeddingClient;
import com.tioka.knowledge.infrastructure.embedding.OpenAiCompatibleEmbeddingClient;
import com.tioka.system.api.spi.EgressGuard;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.tioka.knowledge")
public class KnowledgeAutoConfiguration {

    /**
     * local 默认 Hash 32 维；配置 base-url 后走 OpenAI 兼容 /embeddings，失败回退 hash。
     * 与 schema-pg embedding_vec vector(32) 对齐：请保持 dim=32（或 API 侧输出 32）。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    public EmbeddingClient embeddingClient(
            @Value("${tioka.knowledge.embedding.base-url:}") String baseUrl,
            @Value("${tioka.knowledge.embedding.api-key-ref:}") String apiKeyRef,
            @Value("${tioka.knowledge.embedding.model:text-embedding-3-small}") String model,
            @Value("${tioka.knowledge.embedding.dim:32}") int dim,
            ObjectProvider<SecretStore> secretStore,
            ObjectProvider<EgressGuard> egressGuard,
            ObjectMapper objectMapper) {
        HashEmbeddingClient hash = new HashEmbeddingClient(dim);
        if (baseUrl == null || baseUrl.isBlank()) {
            return hash;
        }
        return new OpenAiCompatibleEmbeddingClient(
                baseUrl, apiKeyRef, model, dim,
                secretStore.getIfAvailable(),
                egressGuard.getIfAvailable(),
                hash,
                objectMapper);
    }
}
