package com.tioka.system.api.spi;

import java.util.Map;
import java.util.Optional;

/** 身份提供方适配器（OIDC / 本地）。 */
public interface IdPAdapter {
    String name();
    Optional<FederatedIdentity> authenticate(Map<String, String> credentials);
    record FederatedIdentity(String subject, String email, String displayName) {}
}
