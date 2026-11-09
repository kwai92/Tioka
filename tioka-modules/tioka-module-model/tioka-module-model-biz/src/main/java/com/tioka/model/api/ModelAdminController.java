package com.tioka.model.api;

import com.tioka.system.api.audit.Audited;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.framework.error.ApiResponse;
import com.tioka.model.api.dto.ProviderDtos.*;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.versioning.ModelConfigVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/model")
@RequirePermission(Permissions.MODEL_ADMIN)
public class ModelAdminController {
    private final ModelAdminService service;
    private final ModelConfigVersionService versioning;

    public ModelAdminController(ModelAdminService service, ModelConfigVersionService versioning) {
        this.service = service;
        this.versioning = versioning;
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

    /** MH-08：别名列表（ADR-041 §1；V156 起 default-plans 由 sys.* 系统别名承担）。 */
    @GetMapping("/aliases")
    public ApiResponse<List<AliasView>> listAliases() {
        return ApiResponse.ok(service.listAliases());
    }

    @PostMapping("/providers")
    @Audited(action = "model.provider.create", resourceType = "model_provider")
    public ApiResponse<ProviderView> createProvider(@RequestBody CreateProviderRequest req) {
        return ApiResponse.ok(service.createProvider(req));
    }

    /** 兼容旧 logical-model 创建（现为无指向别名）；新代码用 POST /aliases。 */
    @Deprecated
    @PostMapping("/logical-models")
    @Audited(action = "model.logical.create", resourceType = "model_alias")
    public ApiResponse<String> createLogicalModel(@RequestBody CreateLogicalModelRequest req) {
        return ApiResponse.ok(service.createLogicalModel(req));
    }

    /** MH-08：创建别名。 */
    @PostMapping("/aliases")
    @Audited(action = "model.alias.create", resourceType = "model_alias")
    public ApiResponse<String> createAlias(@RequestBody CreateAliasRequest req) {
        return ApiResponse.ok(service.createAlias(req));
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

    // ------------------------------------------------- GOV-12 版本化草稿/发布/回滚

    /** 保存草稿（不写审计：ADR-041 §2，草稿以 cfg_draft 最后修改人为准）。 */
    @PostMapping("/configs/{objectType}/{objectId}/draft")
    public ApiResponse<CurrentConfigView> saveDraft(@PathVariable String objectType,
                                                    @PathVariable String objectId,
                                                    @RequestBody SaveConfigDraftRequest req) {
        return ApiResponse.ok(versioning
                .saveDraft(objectType, objectId, req.spec(), req.expectedRevision())
                .map(d -> new CurrentConfigView(objectType, objectId, null, null, "DRAFT", d.spec()))
                .orElse(new CurrentConfigView(objectType, objectId, null, null, "VERSIONING_DISABLED", req.spec())));
    }

    @PostMapping("/configs/{objectType}/{objectId}/publish")
    @Audited(action = "config.publish", resourceType = "model_config", resourceIdArg = "#objectId")
    public ApiResponse<CurrentConfigView> publishConfig(@PathVariable String objectType,
                                                        @PathVariable String objectId,
                                                        @RequestBody(required = false) PublishConfigRequest req) {
        String note = req == null ? null : req.note();
        // 有草稿发布草稿；无草稿则以投影行当前数据登记新版本（与 V151 迁移同口径）
        return ApiResponse.ok(versioning.publishDraft(objectType, objectId, note)
                .or(() -> versioning.publishCurrent(objectType, objectId, note))
                .map(v -> new CurrentConfigView(objectType, objectId, v.versionId(), v.versionNo(),
                        v.status(), v.spec()))
                .orElse(new CurrentConfigView(objectType, objectId, null, null, "VERSIONING_DISABLED", null)));
    }

    @PostMapping("/configs/{objectType}/{objectId}/rollback")
    @Audited(action = "config.rollback", resourceType = "model_config", resourceIdArg = "#objectId")
    public ApiResponse<CurrentConfigView> rollbackConfig(@PathVariable String objectType,
                                                         @PathVariable String objectId,
                                                         @RequestBody RollbackConfigRequest req) {
        return ApiResponse.ok(versioning
                .rollback(objectType, objectId, req.targetVersionNo(), req.reason())
                .map(v -> new CurrentConfigView(objectType, objectId, v.versionId(), v.versionNo(),
                        v.status(), v.spec()))
                .orElse(new CurrentConfigView(objectType, objectId, null, null, "VERSIONING_DISABLED", null)));
    }

    @GetMapping("/configs/{objectType}/{objectId}/current")
    public ApiResponse<CurrentConfigView> currentConfig(@PathVariable String objectType,
                                                        @PathVariable String objectId) {
        return ApiResponse.ok(versioning.current(objectType, objectId)
                .map(v -> new CurrentConfigView(objectType, objectId, v.versionId(), v.versionNo(),
                        v.status(), v.spec()))
                .orElse(new CurrentConfigView(objectType, objectId, null, null, "NO_VERSION", null)));
    }
}
