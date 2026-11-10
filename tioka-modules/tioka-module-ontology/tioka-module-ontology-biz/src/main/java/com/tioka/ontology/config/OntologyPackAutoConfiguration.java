package com.tioka.ontology.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.infra.api.plugin.PluginHost;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.pack.DomainPackLoader;
import com.tioka.ontology.application.pack.PackLoadOutcome;
import com.tioka.ontology.application.pack.PackPluginGateway;
import com.tioka.ontology.application.pack.PackSignatureVerifier;
import com.tioka.ontology.application.pack.PluginHostPackGateway;
import com.tioka.ontology.application.pack.PureFunctionExecutor;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * ONT-05 领域包装配。默认关闭：{@code tioka.ontology.pack.enabled=true} 才注册。
 * 生产网关接 infra FND-12 {@link PluginHost}（{@code tioka.plugin.enabled=true} 时装配）；
 * 宿主未装配时退回进程内加载网关（仅登记，不落 plugin_registry，批次偏差登记）。
 * 验签缺省关闭（{@code tioka.ontology.pack.signature-required=false}），公钥 HEX 注入。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.ontology.pack", name = "enabled", havingValue = "true")
public class OntologyPackAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "tioka.ontology.pack")
    OntologyPackProperties ontologyPackProperties() {
        return new OntologyPackProperties();
    }

    @Bean
    @ConditionalOnMissingBean(PackSignatureVerifier.class)
    PackSignatureVerifier packSignatureVerifier(OntologyPackProperties properties) {
        return new PackSignatureVerifier(properties.getSignaturePublicKeys(), properties.isSignatureRequired());
    }

    @Bean
    @ConditionalOnBean(PluginHost.class)
    @ConditionalOnMissingBean(PackPluginGateway.class)
    PackPluginGateway pluginHostPackGateway(PluginHost host) {
        return new PluginHostPackGateway(host);
    }

    @Bean
    @ConditionalOnMissingBean(PackPluginGateway.class)
    PackPluginGateway localPackGateway() {
        // 宿主未装配（tioka.plugin.enabled=false）的降级：进程内加载（仅登记，不落宿主注册表）
        return new PackPluginGateway() {
            @Override
            public PackLoadOutcome enable(java.nio.file.Path jarPath) {
                return PackLoadOutcome.enabled(
                        jarPath.getFileName().toString(), "local");
            }

            @Override
            public java.util.Optional<Object> getEntry(String pluginKey, String version) {
                return java.util.Optional.empty();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(DomainPackLoader.class)
    DomainPackLoader domainPackLoader(ObjectMapper objectMapper,
                                      PackPluginGateway gateway,
                                      ObjectProvider<DefinitionService> definitionService,
                                      ObjectProvider<SchemaRegistry> schemaRegistry,
                                      PureFunctionExecutor executor,
                                      PackSignatureVerifier verifier) {
        DefinitionService definitions = definitionService.getIfAvailable();
        SchemaRegistry schemas = schemaRegistry.getIfAvailable();
        if (definitions == null) {
            throw new IllegalStateException("DomainPackLoader 需要 DefinitionService（tioka.ontology.definition.enabled=true）");
        }
        return new DomainPackLoader(objectMapper, gateway, definitions, executor, verifier, schemas);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(PureFunctionExecutor.class)
    PureFunctionExecutor packPureFunctionExecutor(OntologyPackProperties properties) {
        return new PureFunctionExecutor(properties.getHookTimeoutSeconds());
    }

    /** {@code tioka.ontology.pack.*}。 */
    public static class OntologyPackProperties {
        private boolean enabled;
        private boolean signatureRequired = false;
        private List<String> signaturePublicKeys = new ArrayList<>();
        private long hookTimeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isSignatureRequired() {
            return signatureRequired;
        }

        public void setSignatureRequired(boolean signatureRequired) {
            this.signatureRequired = signatureRequired;
        }

        public List<String> getSignaturePublicKeys() {
            return signaturePublicKeys;
        }

        public void setSignaturePublicKeys(List<String> signaturePublicKeys) {
            this.signaturePublicKeys = signaturePublicKeys;
        }

        public long getHookTimeoutSeconds() {
            return hookTimeoutSeconds;
        }

        public void setHookTimeoutSeconds(long hookTimeoutSeconds) {
            this.hookTimeoutSeconds = hookTimeoutSeconds;
        }
    }
}
