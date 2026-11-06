package com.tioka.system.application.auth;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.AuthUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final PrincipalLoader loader;
    private final AuthUserRepository users;

    public BearerTokenFilter(TokenService tokens, PrincipalLoader loader, AuthUserRepository users) {
        this.tokens = tokens;
        this.loader = loader;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        try {
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
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
                String wsId = request.getHeader("X-Workspace-Id");
                Principal principal = loader.load(userId, wsId);
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

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
