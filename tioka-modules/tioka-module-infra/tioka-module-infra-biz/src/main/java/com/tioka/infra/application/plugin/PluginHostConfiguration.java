package com.tioka.infra.application.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.infra.api.plugin.PluginHost;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "tioka.plugin", name = "enabled", havingValue = "true")
public class PluginHostConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "tioka.plugin")
    PluginProperties pluginProperties() {
        return new PluginProperties();
    }

    @Bean
    PluginHost pluginHost(ObjectMapper objectMapper, PluginProperties properties) {
        PluginSignatureVerifier verifier = new PluginSignatureVerifier(
                properties.getVerificationKeys(),
                properties.isSignatureRequired());
        return new DefaultPluginHost(objectMapper, verifier);
    }

    public static class PluginProperties {
        private boolean enabled;
        private boolean signatureRequired = true;
        private List<String> verificationKeys = new ArrayList<>();
        private String directory = "/opt/tioka/plugins";

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

        public List<String> getVerificationKeys() {
            return verificationKeys;
        }

        public void setVerificationKeys(List<String> verificationKeys) {
            this.verificationKeys = verificationKeys;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }
}
