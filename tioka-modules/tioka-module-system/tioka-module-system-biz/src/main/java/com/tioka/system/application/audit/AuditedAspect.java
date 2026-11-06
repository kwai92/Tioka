package com.tioka.system.application.audit;

import com.tioka.framework.error.BizException;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.audit.Audited;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link Audited} 切面（GOV-02）。order 尽量靠内，使 SUCCESS 写入落在业务事务内。
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditedAspect {
    private final AuditApi audit;
    private final PrincipalHolder principals;
    private final ExpressionParser parser = new SpelExpressionParser();

    public AuditedAspect(AuditApi audit, PrincipalHolder principals) {
        this.audit = audit;
        this.principals = principals;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        try {
            Object result = pjp.proceed();
            audit.record(build(audited, pjp, result, AuditOutcome.SUCCESS, null));
            return result;
        } catch (BizException ex) {
            AuditOutcome outcome = ex.isDenied() ? AuditOutcome.DENIED : AuditOutcome.ERROR;
            audit.recordIndependent(build(audited, pjp, null, outcome, ex.getMessage()));
            throw ex;
        }
    }

    private AuditEvent build(Audited a, ProceedingJoinPoint pjp, Object result,
                             AuditOutcome outcome, String reason) {
        Principal p = principals.find().orElse(null);
        String actorType = p == null ? "system" : p.type().name().toLowerCase(Locale.ROOT);
        String actorId = p == null ? "system" : p.userId();
        String workspaceId = p == null ? null : p.workspaceId();
        String resourceId = evalResourceId(a.resourceIdArg(), pjp);
        Map<String, Object> details = buildDetails(a.include(), pjp);
        return AuditEvent.builder()
                .ts(Instant.now())
                .workspaceId(workspaceId)
                .actorType(actorType)
                .actorId(actorId)
                .action(a.action())
                .resourceType(blankToNull(a.resourceType()))
                .resourceId(resourceId)
                .outcome(outcome)
                .reason(reason)
                .details(details)
                .build();
    }

    private Map<String, Object> buildDetails(String[] include, ProceedingJoinPoint pjp) {
        if (include == null || include.length == 0) {
            return Map.of();
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Parameter[] params = method.getParameters();
        Object[] args = pjp.getArgs();
        Map<String, Object> merged = new LinkedHashMap<>();
        for (int i = 0; i < params.length && i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg instanceof Map<?, ?> || arg.getClass().isRecord()) {
                merged.putAll(AuditRedaction.pickAndRedact(arg, include));
            } else {
                String name = params[i].getName();
                for (String field : include) {
                    if (field.equals(name)) {
                        merged.put(name, AuditRedaction.isSensitiveName(name) ? "***" : arg);
                    }
                }
            }
        }
        return merged;
    }

    private String evalResourceId(String spel, ProceedingJoinPoint pjp) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Parameter[] params = sig.getMethod().getParameters();
        Object[] args = pjp.getArgs();
        EvaluationContext ctx = new StandardEvaluationContext();
        for (int i = 0; i < params.length && i < args.length; i++) {
            ctx.setVariable(params[i].getName(), args[i]);
        }
        try {
            Expression exp = parser.parseExpression(spel);
            Object v = exp.getValue(ctx);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
