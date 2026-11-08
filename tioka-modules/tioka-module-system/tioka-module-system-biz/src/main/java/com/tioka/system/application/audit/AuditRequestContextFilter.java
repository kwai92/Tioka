package com.tioka.system.application.audit;

import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.id.Ids;
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
 * HTTP 入口建立关联上下文（OBS-01 / OBS-03）：
 * {@code X-Tioka-Request-Id}（兼容 {@code X-Request-Id}）→ MDC + {@link RequestContextHolder}；响应头回写。
 *
 * <p>本过滤器在 Security 之前执行，无法判断登录态；安全形态的 request_id
 * 仍按 OBS-01 采纳（长度与字符集校验）。ADR-073「仅可信来源」收紧放到网关层后续包。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditRequestContextFilter extends OncePerRequestFilter {

    public static final String HEADER_TIOKA = "X-Tioka-Request-Id";
    public static final String HEADER_COMPAT = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String requestId = resolveRequestId(request);
            MDC.put(AuditRequestContext.MDC_REQUEST, requestId);
            AuditRequestContext.currentTraceId();

            RequestContext correlation = RequestContextHolder.getCorrelation().withRequestId(requestId);
            String subject = request.getHeader("X-Tioka-Subject");
            if (subject != null && !subject.isBlank() && subject.length() <= 255) {
                correlation = correlation.withSubject(subject.trim());
            }
            RequestContextHolder.setCorrelation(correlation);

            response.setHeader(HEADER_TIOKA, requestId);
            filterChain.doFilter(request, response);
        } finally {
            RequestContextHolder.clearCorrelation();
            AuditRequestContext.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = firstNonBlank(request.getHeader(HEADER_TIOKA), request.getHeader(HEADER_COMPAT));
        if (incoming != null && isSafeRequestId(incoming)) {
            return incoming.trim();
        }
        return Ids.newId("req");
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
