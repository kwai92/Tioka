package com.tioka.system.application.oidc;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.config.OidcProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FND-05：按 idp 键（如 {@code keycloak}）查找 OIDC 提供方配置与适配器。
 */
@Component
public class OidcProviderRegistry {
    private final OidcProperties props;
    private final Map<String, IdPAdapter> adapters;

    public OidcProviderRegistry(OidcProperties props, EgressClient egress) {
        this.props = props;
        this.adapters = new LinkedHashMap<>();
        if (props.getProviders() != null) {
            for (String idp : props.getProviders().keySet()) {
                adapters.put(idp, new OidcIdPAdapter(idp, props.getProviders().get(idp), egress));
            }
        }
    }

    public boolean enabled() {
        return props.hasProviders();
    }

    public Set<String> providerIds() {
        return Collections.unmodifiableSet(adapters.keySet());
    }

    public OidcProperties.Provider requireProvider(String idp) {
        OidcProperties.Provider p = props.getProviders() == null ? null : props.getProviders().get(idp);
        if (p == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知 OIDC 提供方: " + idp);
        }
        return p;
    }

    public IdPAdapter requireAdapter(String idp) {
        IdPAdapter a = adapters.get(idp);
        if (a == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知 OIDC 提供方: " + idp);
        }
        return a;
    }

    public Optional<String> findIdpByIssuer(String issuer) {
        if (issuer == null || props.getProviders() == null) {
            return Optional.empty();
        }
        String norm = issuer.replaceAll("/$", "");
        for (Map.Entry<String, OidcProperties.Provider> e : props.getProviders().entrySet()) {
            String iss = e.getValue().getIssuerUri();
            if (iss != null && norm.equals(iss.replaceAll("/$", ""))) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    public OidcProperties properties() {
        return props;
    }
}
