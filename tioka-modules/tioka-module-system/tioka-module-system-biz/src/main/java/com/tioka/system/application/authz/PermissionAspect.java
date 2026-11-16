package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PermissionEvaluator;
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
 *
 * <p>判定来源委托给 {@link PermissionEvaluator}：{@code local} 模式读应用自建权限集，
 * {@code foundation} 模式读中台授权快照（经权限码映射）。切面只负责注解解析、
 * 拒绝审计与 403 语义，不关心权限从哪里来；两个判定器由模式开关互斥装配。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class PermissionAspect {
    private final PrincipalHolder principals;
    private final PermissionEvaluator permissions;
    private final AuditApi audit;

    public PermissionAspect(PrincipalHolder principals, PermissionEvaluator permissions, AuditApi audit) {
        this.principals = principals;
        this.permissions = permissions;
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
        String missing = permissions.firstMissing(p, required);
        if (missing != null) {
            deny(p, required, missing);
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
