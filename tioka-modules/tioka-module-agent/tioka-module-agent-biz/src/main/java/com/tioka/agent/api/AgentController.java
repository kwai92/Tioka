package com.tioka.agent.api;

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

    @RequirePermission(Permissions.AGENT_WRITE)
    @PostMapping("/{id}/drafts")
    public ApiResponse<AgentStore.AgentVersion> draft(@PathVariable String id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> grants = (List<String>) body.getOrDefault("mcpGrantIds", List.of());
        List<AgentStore.SkillBinding> skills = parseSkillBindings(body.get("skillBindings"), body.get("skillIds"));
        return ApiResponse.ok(service.draft(id, String.valueOf(body.getOrDefault("instructions", "")),
                String.valueOf(body.get("routingPlanId")), grants, skills));
    }

    @SuppressWarnings("unchecked")
    private static List<AgentStore.SkillBinding> parseSkillBindings(Object bindingsRaw, Object skillIdsRaw) {
        List<AgentStore.SkillBinding> out = new java.util.ArrayList<>();
        if (bindingsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object sid = m.get("skillId");
                    if (sid == null) continue;
                    Object mode = m.containsKey("mode") ? m.get("mode") : "auto";
                    out.add(new AgentStore.SkillBinding(String.valueOf(sid), String.valueOf(mode)));
                } else if (item != null) {
                    out.add(new AgentStore.SkillBinding(String.valueOf(item), "always"));
                }
            }
        }
        if (skillIdsRaw instanceof List<?> ids) {
            for (Object id : ids) {
                if (id == null) continue;
                String sid = String.valueOf(id);
                boolean exists = out.stream().anyMatch(b -> sid.equals(b.skillId()));
                if (!exists) out.add(new AgentStore.SkillBinding(sid, "always"));
            }
        }
        return out;
    }

    @RequirePermission(Permissions.AGENT_PUBLISH)
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<AgentStore.AgentVersion> publish(@PathVariable String versionId) {
        return ApiResponse.ok(service.publish(versionId));
    }
}
