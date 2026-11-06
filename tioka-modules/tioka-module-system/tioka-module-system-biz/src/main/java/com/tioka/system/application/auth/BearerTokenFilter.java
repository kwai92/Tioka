package com.tioka.system.application.auth;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.system.application.oidc.IdPAdapter;
import com.tioka.system.application.oidc.OidcFederationService;
import com.tioka.system.application.oidc.OidcIdPAdapter;
import com.tioka.system.application.oidc.OidcProviderRegistry;
import com.tioka.system.config.OidcProperties;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FND-05：{@code tka_} → Redis；三段式 JWT 且 resource-server.enabled → IdP JWT；{@code tk_live_} 留给 FND-06。
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final PrincipalLoader loader;
    private final AuthUserRepository users;
    private final ObjectProvider<OidcProviderRegistry> oidcRegistry;
    private final ObjectProvider<OidcFederationService> oidcFederation;

    public BearerTokenFilter(TokenService tokens,
                             PrincipalLoader loader,
                             AuthUserRepository users,
                             ObjectProvider<OidcProviderRegistry> oidcRegistry,
                             ObjectProvider<OidcFederationService> oidcFederation) {
        this.tokens = tokens;
        this.loader = loader;
        this.users = users;
        this.oidcRegistry = oidcRegistry;
        this.oidcFederation = oidcFederation;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        try {
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                String wsId = request.getHeader("X-Workspace-Id");
                Principal principal;
                if (token.startsWith("tka_")) {
                    principal = loadOpaque(token, wsId);
                } else if (token.startsWith("tk_live_")) {
                    // FND-06
                    throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
                } else if (looksLikeJwt(token) && resourceServerEnabled()) {
                    principal = loadOidcJwt(token, wsId);
                } else {
                    throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
                }
                SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(principal));
                MDC.put("uid", principal.userId());
                if (principal.workspaceId() != null) {
                    MDC.put("ws", principal.workspaceId());
                }
            }
            filterChain.doFilter(request, response);
        } catch (BizException ex) {
            SecurityContextHolder.clearContext();
            response.setStatus(ex.getErrorCode().getHttpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + ex.getErrorCode().getCode()
                    + ",\"message\":\"" + escape(ex.getMessage()) + "\",\"data\":null}");
        } finally {
            SecurityContextHolder.clearContext();
            MDC.remove("uid");
            MDC.remove("ws");
        }
    }

    private Principal loadOpaque(String token, String wsId) {
        Optional<TokenService.TokenClaims> claims = tokens.verifyAccess(token);
        if (claims.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        long userId = claims.get().userId();
        AuthUserDO user = users.findById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            tokens.revokeAll(userId);
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        String method = user.getIdp() != null && user.getIdp().startsWith("oidc:") ? "oidc" : "password";
        return loader.load(userId, wsId, method);
    }

    private Principal loadOidcJwt(String token, String wsId) {
        OidcProviderRegistry registry = oidcRegistry.getIfAvailable();
        OidcFederationService federation = oidcFederation.getIfAvailable();
        if (registry == null || federation == null || !registry.enabled()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        OidcProperties props = registry.properties();
        List<String> audiences = props.getResourceServer().getAudiences();
        Jwt jwt = null;
        String idp = null;
        OidcProperties.Provider provider = null;
        for (String candidate : registry.providerIds()) {
            try {
                OidcIdPAdapter adapter = (OidcIdPAdapter) registry.requireAdapter(candidate);
                Jwt decoded = adapter.decodeAccessToken(token);
                idp = candidate;
                provider = adapter.provider();
                jwt = decoded;
                break;
            } catch (RuntimeException ignored) {
                // 试下一个 issuer
            }
        }
        if (jwt == null || idp == null || provider == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或令牌已过期");
        }
        if (!audienceAccepted(jwt, audiences)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌受众不匹配");
        }
        IdPAdapter.FederatedIdentity identity = toIdentity(jwt, provider);
        AuthUserDO user = federation.resolveAndSync(idp, provider, identity);
        return loader.load(user.getId(), wsId, "oidc_jwt");
    }

    private static IdPAdapter.FederatedIdentity toIdentity(Jwt jwt, OidcProperties.Provider provider) {
        String sub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String preferred = jwt.getClaimAsString("preferred_username");
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) {
            name = preferred != null ? preferred : (email != null ? email : sub);
        }
        List<String> groups = new ArrayList<>();
        String claim = provider.getGroupsClaim() == null || provider.getGroupsClaim().isBlank()
                ? "groups" : provider.getGroupsClaim();
        Object raw = jwt.getClaim(claim);
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    groups.add(String.valueOf(o));
                }
            }
        }
        return new IdPAdapter.FederatedIdentity(sub, email, name, preferred, groups);
    }

    private static boolean audienceAccepted(Jwt jwt, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        List<String> aud = jwt.getAudience();
        if (aud != null) {
            for (String e : expected) {
                if (aud.contains(e)) {
                    return true;
                }
            }
        }
        Object raw = jwt.getClaim("aud");
        if (raw instanceof String s) {
            return expected.contains(s);
        }
        return false;
    }

    private boolean resourceServerEnabled() {
        OidcProviderRegistry registry = oidcRegistry.getIfAvailable();
        return registry != null
                && registry.enabled()
                && registry.properties().getResourceServer() != null
                && registry.properties().getResourceServer().isEnabled();
    }

    private static boolean looksLikeJwt(String token) {
        int dots = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '.') {
                dots++;
            }
        }
        return dots == 2;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
