package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PermissionEvaluator;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.PublicApi;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限切面委托后的行为契约（不依赖容器，可在无 Docker 环境运行）。
 *
 * <p>验证改造只替换了「权限从哪里判定」这一处，放行、403、拒绝审计与公开接口语义不变。
 */
class PermissionAspectUnitTest {

    private static final Principal ACTOR =
            new Principal("u-1", "s-1", PrincipalType.USER, Set.of(), Set.of(), "password");

    @Test
    void 判定通过时放行且不写拒绝审计() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("write");
        PermissionEvaluator evaluator = evaluatorReturning(null);
        AuditApi audit = mock(AuditApi.class);

        Object result = aspect(evaluator, audit).around(pjp);

        assertThat(result).isEqualTo("ok");
        verify(pjp).proceed();
        verify(audit, never()).recordIndependent(any());
    }

    @Test
    void 判定拒绝时抛403且写DENIED审计() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("write");
        PermissionEvaluator evaluator = evaluatorReturning(Permissions.AGENT_WRITE);
        AuditApi audit = mock(AuditApi.class);

        assertThatThrownBy(() -> aspect(evaluator, audit).around(pjp))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN))
                .hasMessageContaining("无权限");

        verify(audit).recordIndependent(argThat(
                event -> event.outcome() == AuditOutcome.DENIED));
        verify(pjp, never()).proceed();
    }

    @Test
    void 公开接口直接放行且不调用判定器() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("publicWrite");
        PermissionEvaluator evaluator = evaluatorReturning(Permissions.AGENT_WRITE);
        AuditApi audit = mock(AuditApi.class);

        assertThat(aspect(evaluator, audit).around(pjp)).isEqualTo("ok");
        verify(pjp).proceed();
        verify(evaluator, never()).firstMissing(any(), any());
        verify(audit, never()).recordIndependent(any());
    }

    @Test
    void 空权限声明只校验登录不调用判定器() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("loggedInOnly");
        PermissionEvaluator evaluator = evaluatorReturning(Permissions.AGENT_WRITE);
        AuditApi audit = mock(AuditApi.class);

        assertThat(aspect(evaluator, audit).around(pjp)).isEqualTo("ok");
        verify(evaluator, never()).firstMissing(any(), any());
        verify(pjp).proceed();
    }

    private static PermissionAspect aspect(PermissionEvaluator evaluator, AuditApi audit) {
        PrincipalHolder principals = mock(PrincipalHolder.class);
        when(principals.current()).thenReturn(ACTOR);
        return new PermissionAspect(principals, evaluator, audit);
    }

    private static PermissionEvaluator evaluatorReturning(String missing) {
        PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
        when(evaluator.firstMissing(any(), any())).thenReturn(missing);
        return evaluator;
    }

    private static ProceedingJoinPoint joinPoint(String methodName) throws Throwable {
        Guarded target = new Guarded();
        Method method = Guarded.class.getMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(target);
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }

    public static class Guarded {

        @RequirePermission(Permissions.AGENT_WRITE)
        public String write() {
            return "ok";
        }

        @RequirePermission({})
        public String loggedInOnly() {
            return "ok";
        }

        @PublicApi(reason = "切面单元测试用公开入口")
        @RequirePermission(Permissions.AGENT_WRITE)
        public String publicWrite() {
            return "ok";
        }
    }
}
