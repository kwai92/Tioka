package com.tioka.model.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.model.api.dto.ProviderDtos.*;
import com.tioka.model.application.ModelAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/model")
public class ModelAdminController {
    private final ModelAdminService service;

    public ModelAdminController(ModelAdminService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public ApiResponse<List<ProviderView>> listProviders() {
        return ApiResponse.ok(service.listProviders());
    }

    @GetMapping("/routing-plans")
    public ApiResponse<List<RoutingPlanView>> listPlans() {
        return ApiResponse.ok(service.listPlans());
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

    @PostMapping("/routing-plans")
    public ApiResponse<RoutingPlanView> createPlan(@RequestBody CreateRoutingPlanRequest req) {
        return ApiResponse.ok(service.createPlan(req));
    }

    @PostMapping("/routing-plans/{id}/publish")
    public ApiResponse<RoutingPlanView> publish(@PathVariable String id) {
        return ApiResponse.ok(service.publish(id));
    }
}
