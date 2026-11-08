package com.tioka.framework.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * FND-08：装配 {@link CredentialCipher} 与重加密登记表。
 */
@AutoConfiguration
public class CredentialSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CredentialSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CredentialCipher.class)
    CredentialCipher credentialCipher(
            @Value("${tioka.credential.master-key:}") String masterKey,
            @Value("${tioka.credential.key-version:1}") int keyVersion,
            @Value("${tioka.credential.require-external-key:false}") boolean requireExternalKey,
            Environment environment) {
        boolean mustExternal = requireExternalKey
                || (environment != null && environment.acceptsProfiles(Profiles.of("prod")));
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(masterKey, keyVersion, mustExternal);
        if (cipher.usingDevFallback()) {
            log.warn("CredentialCipher 使用内置开发主密钥；请通过 tioka.credential.master-key / TIOKA_CREDENTIAL_MASTER_KEY 配置外部密钥");
        }
        return cipher;
    }

    @Bean
    @ConditionalOnMissingBean(CredentialReencryptRegistry.class)
    CredentialReencryptRegistry credentialReencryptRegistry() {
        InMemoryCredentialReencryptRegistry registry = new InMemoryCredentialReencryptRegistry();
        registry.register(new CredentialReencryptRegistry.CredentialColumn("secret_store", "cipher_text"));
        return registry;
    }
}
