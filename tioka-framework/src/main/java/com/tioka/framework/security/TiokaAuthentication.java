package com.tioka.framework.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.stream.Collectors;

public class TiokaAuthentication extends AbstractAuthenticationToken {
    private final Principal principal;

    public TiokaAuthentication(Principal principal) {
        super(principal.permissions().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList()));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Principal getPrincipal() {
        return principal;
    }
}
