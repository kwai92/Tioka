package com.tioka.connector.api;

import com.tioka.connector.application.im.ImBindingService;
import com.tioka.connector.application.im.ImBindingService.ConnectivityCheckResult;
import com.tioka.connector.application.im.ImBindingService.CreateImBindingRequest;
import com.tioka.connector.application.im.ImBindingService.ImBindingView;
import com.tioka.connector.application.im.ImBindingService.UpdateImBindingRequest;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.audit.Audited;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IM 绑定管理接口（IM-ADM）。密钥只写不读；回调地址由服务端生成。
 */
@RestController
@RequestMapping("/api/connector/im/bindings")
@RequirePermission(Permissions.WORKSPACE_MANAGE)
public class ImBindingController {

    private final ImBindingService service;

    public ImBindingController(ImBindingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ImBindingView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ImBindingView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @Audited(action = "im.binding.create", resourceType = "im_binding")
    public ApiResponse<ImBindingView> create(@RequestBody CreateImBindingRequest body) {
        return ApiResponse.ok(service.create(body));
    }

    @PatchMapping("/{id}")
    @Audited(action = "im.binding.update", resourceType = "im_binding", resourceIdArg = "#id")
    public ApiResponse<ImBindingView> update(@PathVariable String id,
                                             @RequestBody UpdateImBindingRequest body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @Audited(action = "im.binding.delete", resourceType = "im_binding", resourceIdArg = "#id")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/connectivity")
    public ApiResponse<ConnectivityCheckResult> connectivity(@PathVariable String id) {
        return ApiResponse.ok(service.checkConnectivity(id));
    }
}
