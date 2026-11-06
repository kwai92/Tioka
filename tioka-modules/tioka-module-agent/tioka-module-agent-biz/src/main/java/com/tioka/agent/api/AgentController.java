package com.tioka.agent.api;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @RequirePermission(Permissions.AGENT_READ)
    @GetMapping
    public ApiResponse<List<AgentStore.Agent>> list() {
        return ApiResponse.ok(service.list());
    }

    @RequirePermission(Permissions.AGENT_READ)
    @GetMapping("/{id}")
    public ApiResponse<AgentStore.Agent> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @RequirePermission(Permissions.AGENT_WRITE)
    @PostMapping
    public ApiResponse<AgentStore.Agent> create(@Valid @RequestBody CreateAgentRequest body) {
        return ApiResponse.ok(service.create(body.getName(), body.getWorkspaceId(), body.getVisibility()));
    }

    @RequirePermission(Permissions.AGENT_WRITE)
    @PutMapping("/{id}")
    public ApiResponse<AgentStore.Agent> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        String visibility = body.get("visibility") == null ? null : String.valueOf(body.get("visibility"));
        return ApiResponse.ok(service.update(id, name, visibility));
    }

    @RequirePermission(Permissions.AGENT_READ)
    @GetMapping("/{id}/draft")
    public ApiResponse<AgentService.DraftView> getDraft(@PathVariable String id) {
        return ApiResponse.ok(service.getDraft(id));
    }

    @RequirePermission(Permissions.AGENT_WRITE)
    @PutMapping("/{id}/draft")
    public ApiResponse<AgentService.DraftView> putDraft(@PathVariable String id,
                                                        @RequestBody DraftBody body) {
        return ApiResponse.ok(service.saveDraft(id, body.spec(), body.revision()));
    }

    @RequirePermission(Permissions.AGENT_WRITE)
    @PostMapping("/{id}/validate")
    public ApiResponse<AgentService.ValidateResult> validate(@PathVariable String id) {
        return ApiResponse.ok(service.validate(id));
    }

    @RequirePermission(Permissions.AGENT_PUBLISH)
    @PostMapping("/{id}/publish")
    public ApiResponse<AgentService.PublishResult> publish(@PathVariable String id,
                                                           @RequestBody PublishBody body) {
        return ApiResponse.ok(service.publish(id, body.revision(), body.note()));
    }

    @RequirePermission(Permissions.AGENT_READ)
    @GetMapping("/{id}/versions")
    public ApiResponse<List<AgentService.VersionView>> versions(@PathVariable String id) {
        return ApiResponse.ok(service.listVersions(id));
    }

    @RequirePermission(Permissions.AGENT_READ)
    @GetMapping("/{id}/versions/{versionId}")
    public ApiResponse<AgentService.VersionView> version(@PathVariable String id,
                                                         @PathVariable String versionId) {
        return ApiResponse.ok(service.getVersion(id, versionId));
    }

    @RequirePermission(Permissions.AGENT_READ)
    @GetMapping("/{id}/versions/diff")
    public ApiResponse<List<Map<String, Object>>> diff(@PathVariable String id,
                                                       @RequestParam String from,
                                                       @RequestParam String to) {
        return ApiResponse.ok(service.diff(id, from, to));
    }

    @RequirePermission(Permissions.AGENT_PUBLISH)
    @PostMapping("/{id}/rollback")
    public ApiResponse<AgentService.PublishResult> rollback(@PathVariable String id,
                                                            @RequestBody RollbackBody body) {
        return ApiResponse.ok(service.rollback(id, body.versionId()));
    }

    @RequirePermission(Permissions.AGENT_PUBLISH)
    @PostMapping("/{id}/disable")
    public ApiResponse<AgentStore.Agent> disable(@PathVariable String id) {
        return ApiResponse.ok(service.disable(id));
    }

    @RequirePermission(Permissions.AGENT_PUBLISH)
    @PostMapping("/{id}/enable")
    public ApiResponse<AgentStore.Agent> enable(@PathVariable String id) {
        return ApiResponse.ok(service.enable(id));
    }

    public record DraftBody(AgentSpec spec, int revision) {}

    public record PublishBody(int revision, String note) {}

    public record RollbackBody(String versionId) {}
}
