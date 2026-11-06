package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PublicApi;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * {@link RequirePermission} 功能权限切面（FND-04）。order 早于事务。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class PermissionAspect {
    private final PrincipalHolder principals;
    private final AuditApi audit;

    public PermissionAspect(PrincipalHolder principals, AuditApi audit) {
        this.principals = principals;
        this.audit = audit;
    }

    @Around("@within(com.tioka.framework.security.RequirePermission) || @annotation(com.tioka.framework.security.RequirePermission)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        if (AnnotationUtils.findAnnotation(method, PublicApi.class) != null) {
            return pjp.proceed();
        }
        RequirePermission ann = AnnotationUtils.findAnnotation(method, RequirePermission.class);
        if (ann == null) {
            ann = AnnotationUtils.findAnnotation(pjp.getTarget().getClass(), RequirePermission.class);
        }
        if (ann == null) {
            return pjp.proceed();
        }
        String[] required = ann.value();
        if (required.length == 0) {
            // 仅需已登录（Principal 存在）
            principals.current();
            return pjp.proceed();
        }
        Principal p = principals.current();
        for (String perm : required) {
            if (!p.hasPermission(perm)) {
                deny(p, required, perm);
            }
        }
        return pjp.proceed();
    }

    private void deny(Principal p, String[] required, String missing) {
        String action = Arrays.stream(required)
                .map(s -> s.replace(':', '.'))
                .collect(Collectors.joining(","));
        try {
            audit.recordIndependent(AuditEvent.builder()
                    .ts(Instant.now())
                    .workspaceId(p.workspaceId())
                    .actorType(p.type().name().toLowerCase())
                    .actorId(p.userId())
                    .action(action.isBlank() ? "permission.denied" : action)
                    .outcome(AuditOutcome.DENIED)
                    .reason("缺少权限: " + missing)
                    .build());
        } catch (RuntimeException ignored) {
            // 审计失败不吞权限拒绝
        }
        throw new BizException(ErrorCode.FORBIDDEN, "无权限: " + missing);
    }
}
