package com.tioka.agent.api;

import java.util.List;

public interface AgentQueryApi {
    AgentVersionView requirePublished(String versionId);

    record SkillBindingView(String skillId, String name, String mode, String description, String skillMd) {}

    record AgentVersionView(String id, String agentId, String instructions, String routingPlanId,
                            List<String> mcpGrantIds, List<SkillBindingView> skills) {
        public AgentVersionView(String id, String agentId, String instructions, String routingPlanId,
                                List<String> mcpGrantIds) {
            this(id, agentId, instructions, routingPlanId, mcpGrantIds, List.of());
        }
    }
}
