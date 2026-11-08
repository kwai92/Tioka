package com.tioka.model.api;

import com.tioka.system.api.audit.Audited;

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
    public ApiResponse<List<ModelBindingView>> listModelBindings() {
        return ApiResponse.ok(service.listModelBindings());
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
    @Audited(action = "model.provider.create", resourceType = "model_provider")
    public ApiResponse<ProviderView> createProvider(@RequestBody CreateProviderRequest req) {
        return ApiResponse.ok(service.createProvider(req));
    }

    @PostMapping("/logical-models")
    @Audited(action = "model.logical.create", resourceType = "logical_model")
    public ApiResponse<String> createLogicalModel(@RequestBody CreateLogicalModelRequest req) {
        return ApiResponse.ok(service.createLogicalModel(req));
    }

    @PostMapping("/bindings")
    @Audited(action = "model.binding.create", resourceType = "model_binding")
    public ApiResponse<String> createModelBinding(@RequestBody CreateModelBindingRequest req) {
        return ApiResponse.ok(service.createModelBinding(req));
    }

    @PatchMapping("/bindings/{id}")
    @Audited(action = "model.binding.update", resourceType = "model_binding", resourceIdArg = "#id")
    public ApiResponse<ModelBindingView> updateModelBinding(@PathVariable String id,
                                                  @RequestBody UpdateModelBindingRequest req) {
        return ApiResponse.ok(service.updateModelBinding(id, req));
    }

    @PostMapping("/routing-plans")
    @Audited(action = "model.routing_plan.create", resourceType = "routing_plan")
    public ApiResponse<RoutingPlanView> createPlan(@RequestBody CreateRoutingPlanRequest req) {
        return ApiResponse.ok(service.createPlan(req));
    }

    @PostMapping("/routing-plans/{id}/publish")
    @Audited(action = "model.routing_plan.publish", resourceType = "routing_plan", resourceIdArg = "#id")
    public ApiResponse<RoutingPlanView> publish(@PathVariable String id) {
        return ApiResponse.ok(service.publish(id));
    }

    @PutMapping("/default-plans/{purpose}")
    @Audited(action = "model.default_plan.upsert", resourceType = "default_plan", resourceIdArg = "#purpose")
    public ApiResponse<DefaultPlanView> upsertDefaultPlan(
            @PathVariable String purpose,
            @RequestBody UpsertDefaultPlanRequest req) {
        return ApiResponse.ok(service.upsertDefaultPlan(purpose, req));
    }
}
