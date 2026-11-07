package com.tioka.system.application.oidc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OIDC / 未来 SAML·LDAP 身份提供方内部 SPI（FND-05；原 system-api {@code IdPAdapter} 迁入）。
 * 由 {@link OidcProviderRegistry} 持有；M4 的 SAML/LDAP 实现同一接口。
 */
public interface IdPAdapter {
    String name();

    Optional<FederatedIdentity> authenticate(Map<String, String> credentials);

    record FederatedIdentity(
            String subject,
            String email,
            String displayName,
            String preferredUsername,
            List<String> groups) {
        public FederatedIdentity {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }
}
