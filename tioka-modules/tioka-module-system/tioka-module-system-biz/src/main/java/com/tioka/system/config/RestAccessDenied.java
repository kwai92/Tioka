package com.tioka.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * Spring Security 403：写 DENIED 审计（GOV-02）。
 */
@Component
public class RestAccessDenied implements AccessDeniedHandler {
    private final ObjectMapper objectMapper;
    private final AuditApi audit;
    private final PrincipalHolder principals;

    public RestAccessDenied(ObjectMapper objectMapper, AuditApi audit, PrincipalHolder principals) {
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.principals = principals;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        try {
            Principal p = principals.find().orElse(null);
            audit.recordIndependent(AuditEvent.builder()
                    .ts(Instant.now())
                    .workspaceId(p == null ? null : p.workspaceId())
                    .actorType(p == null ? "anonymous" : p.type().name().toLowerCase(Locale.ROOT))
                    .actorId(p == null ? "anonymous" : p.userId())
                    .action("http.access_denied")
                    .resourceType("http")
                    .resourceId(request.getRequestURI())
                    .outcome(AuditOutcome.DENIED)
                    .reason(accessDeniedException.getMessage())
                    .clientIp(request.getRemoteAddr())
                    .build());
        } catch (RuntimeException ignored) {
            // 审计失败不影响 403 响应
        }
        response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.fail(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage()));
    }
}
