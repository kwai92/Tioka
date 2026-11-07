package com.tioka.model.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.model.api.dto.ProviderDtos.*;
import com.tioka.model.application.ModelAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/model")
@RequirePermission(Permissions.MODEL_ADMIN)
public class ModelAdminController {
    private final ModelAdminService service;

    public ModelAdminController(ModelAdminService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public ApiResponse<List<ProviderView>> listProviders() {
        return ApiResponse.ok(service.listProviders());
    }

    @GetMapping("/bindings")
    public ApiResponse<List<BindingView>> listBindings() {
        return ApiResponse.ok(service.listBindings());
    }

    @GetMapping("/routing-plans")
    public ApiResponse<List<RoutingPlanView>> listPlans() {
        return ApiResponse.ok(service.listPlans());
    }

    @GetMapping("/default-plans")
    public ApiResponse<List<DefaultPlanView>> listDefaultPlans() {
        return ApiResponse.ok(service.listDefaultPlans());
    }

    @PostMapping("/providers")
    public ApiResponse<ProviderView> createProvider(@RequestBody CreateProviderRequest req) {
        return ApiResponse.ok(service.createProvider(req));
    }

    @PostMapping("/logical-models")
    public ApiResponse<String> createLogicalModel(@RequestBody CreateLogicalModelRequest req) {
        return ApiResponse.ok(service.createLogicalModel(req));
    }

    @PostMapping("/bindings")
    public ApiResponse<String> createBinding(@RequestBody CreateBindingRequest req) {
        return ApiResponse.ok(service.createBinding(req));
    }

    @PatchMapping("/bindings/{id}")
    public ApiResponse<BindingView> updateBinding(@PathVariable String id,
                                                  @RequestBody UpdateBindingRequest req) {
        return ApiResponse.ok(service.updateBinding(id, req));
    }

    @PostMapping("/routing-plans")
    public ApiResponse<RoutingPlanView> createPlan(@RequestBody CreateRoutingPlanRequest req) {
        return ApiResponse.ok(service.createPlan(req));
    }

    @PostMapping("/routing-plans/{id}/publish")
    public ApiResponse<RoutingPlanView> publish(@PathVariable String id) {
        return ApiResponse.ok(service.publish(id));
    }

    @PutMapping("/default-plans/{purpose}")
    public ApiResponse<DefaultPlanView> upsertDefaultPlan(
            @PathVariable String purpose,
            @RequestBody UpsertDefaultPlanRequest req) {
        return ApiResponse.ok(service.upsertDefaultPlan(purpose, req));
    }
}
