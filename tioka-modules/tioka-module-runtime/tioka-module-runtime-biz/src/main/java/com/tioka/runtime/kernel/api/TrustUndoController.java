package com.tioka.runtime.kernel.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.application.trust.UndoCircuitBreaker;
import com.tioka.runtime.application.trust.UndoCircuitPolicy;
import com.tioka.runtime.application.trust.UndoService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TRU-07 撤销接口（ADR-033「撤销」）：{@code POST /api/trust/undo {scope, id}}；
 * TRU-08 熔断阈值：{@code PUT /api/trust/undo/circuit-policy}。
 *
 * <p>鉴权（五类可撤销人）在 {@code UndoAuthorizer}（服务级）：Turn 发起人、工单请求人、
 * Agent 负责人、批准过该动作的审批人、空间管理员。本控制器 {@code @RequirePermission}
 * 空权限点（仅需已登录，PermissionAspect 语义）：五路身份是并集而非单一权限点，
 * 细判交由 {@code UndoAuthorizer}。配置端点不是五路身份并集，而是空间级治理配置，
 * 必须 {@code workspace:manage}（否则任意成员都能把阈值调到 MAX 禁用熔断）；
 * workspaceId 只取当前 Principal，不接受跨空间写入。
 */
@RestController
@RequestMapping("/api/trust")
public class TrustUndoController {

    private final UndoService undo;
    private final PrincipalHolder principals;
    private final UndoCircuitBreaker breaker;

    public TrustUndoController(UndoService undo, PrincipalHolder principals,
                               UndoCircuitBreaker breaker) {
        this.undo = undo;
        this.principals = principals;
        this.breaker = breaker;
    }

    @PostMapping("/undo")
    @RequirePermission({})
    public ApiResponse<UndoService.UndoResult> undo(@RequestBody Map<String, Object> body) {
        String scope = body.get("scope") == null ? null : String.valueOf(body.get("scope"));
        String id = body.get("id") == null ? null : String.valueOf(body.get("id"));
        Principal principal = principals.find().orElse(null);
        return ApiResponse.ok(undo.undo(scope, id, principal));
    }

    /** 空间级熔断阈值配置；每次变化由服务层写审计（M3-05 §6）。 */
    @PutMapping("/undo/circuit-policy")
    @RequirePermission(Permissions.WORKSPACE_MANAGE)
    public ApiResponse<UndoCircuitPolicy> configureCircuitPolicy(@RequestBody Map<String, Object> body) {
        Principal principal = principals.find()
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN, "熔断策略配置需要登录身份"));
        String workspaceId = principal.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            // 空 workspace 会写出永不被读取的孤儿策略行（policies.find 对空值返回缺省）
            throw new BizException(ErrorCode.BAD_REQUEST, "熔断策略配置缺少工作空间");
        }
        int undoThreshold = intOf(body.get("undoThreshold"), UndoCircuitPolicy.DEFAULT_UNDO_THRESHOLD);
        int failThreshold = intOf(body.get("compFailThreshold"),
                UndoCircuitPolicy.DEFAULT_COMP_FAIL_THRESHOLD);
        int windowMinutes = intOf(body.get("windowMinutes"), UndoCircuitPolicy.DEFAULT_WINDOW_MINUTES);
        return ApiResponse.ok(breaker.configure(workspaceId, undoThreshold, failThreshold,
                windowMinutes, principal.userId()));
    }

    private static int intOf(Object value, int fallback) {
        if (!(value instanceof Number n)) {
            return fallback;
        }
        return n.intValue();
    }
}
