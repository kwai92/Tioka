package com.tioka.runtime.kernel.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.application.trust.UndoService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TRU-07 撤销接口（ADR-033「撤销」）：{@code POST /api/trust/undo {scope, id}}。
 *
 * <p>鉴权（五类可撤销人）在 {@code UndoAuthorizer}（服务级）：Turn 发起人、工单请求人、
 * Agent 负责人、批准过该动作的审批人、空间管理员。本控制器 {@code @RequirePermission}
 * 空权限点（仅需已登录，PermissionAspect 语义）：五路身份是并集而非单一权限点，
 * 细判交由 {@code UndoAuthorizer}。
 */
@RestController
@RequestMapping("/api/trust")
public class TrustUndoController {

    private final UndoService undo;
    private final PrincipalHolder principals;

    public TrustUndoController(UndoService undo, PrincipalHolder principals) {
        this.undo = undo;
        this.principals = principals;
    }

    @PostMapping("/undo")
    @RequirePermission({})
    public ApiResponse<UndoService.UndoResult> undo(@RequestBody Map<String, Object> body) {
        String scope = body.get("scope") == null ? null : String.valueOf(body.get("scope"));
        String id = body.get("id") == null ? null : String.valueOf(body.get("id"));
        Principal principal = principals.find().orElse(null);
        return ApiResponse.ok(undo.undo(scope, id, principal));
    }
}
