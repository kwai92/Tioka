package com.tioka.server.team;

import com.tioka.agent.application.SubAgentPolicy;
import com.tioka.framework.error.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/runtime/team")
public class TeamRunController {
    private final SubAgentPolicy policy;

    public TeamRunController(SubAgentPolicy policy) {
        this.policy = policy;
    }

    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        String lead = String.valueOf(body.get("leadAgentId"));
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) body.getOrDefault("members", List.of());
        String task = String.valueOf(body.getOrDefault("task", ""));
        int depth = 0;
        List<String> results = new ArrayList<>();
        for (String m : members) {
            policy.assertDelegable(members, m, depth);
            results.add(m + ":done:" + task);
            depth++;
        }
        return ApiResponse.ok(Map.of("lead", lead, "summary", String.join(" | ", results)));
    }
}
