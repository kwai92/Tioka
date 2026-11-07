package com.tioka.system.application.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 入口建立审计 MDC（OBS-01）：合法 {@code X-Request-Id} 复用，否则生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String incoming = request.getHeader("X-Request-Id");
            if (incoming != null && isSafeRequestId(incoming)) {
                MDC.put(AuditRequestContext.MDC_REQUEST, incoming.trim());
            } else {
                AuditRequestContext.currentRequestId();
            }
            AuditRequestContext.currentTraceId();
            filterChain.doFilter(request, response);
        } finally {
            AuditRequestContext.clear();
        }
    }

    static boolean isSafeRequestId(String value) {
        String v = value.trim();
        if (v.isEmpty() || v.length() > 64) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return true;
    }
}
