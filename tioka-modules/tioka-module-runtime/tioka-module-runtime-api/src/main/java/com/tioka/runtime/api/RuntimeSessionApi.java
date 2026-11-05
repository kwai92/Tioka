package com.tioka.runtime.api;

import java.util.List;
import java.util.Map;

public interface RuntimeSessionApi {
    SessionView create(CreateSessionRequest request);
    TurnView append(String sessionId, AppendRequest request);
    void cancel(String sessionId);
    void steer(String sessionId, String text);
    SessionView get(String sessionId);

    record CreateSessionRequest(String agentVersionId, String routingPlanId, Map<String, Object> meta,
                                List<SkillInstallInput> skills) {
        public CreateSessionRequest(String agentVersionId, String routingPlanId, Map<String, Object> meta) {
            this(agentVersionId, routingPlanId, meta, List.of());
        }
    }

    record SkillInstallInput(String skillId, String name, String mode, String description, String skillMd) {}

    record AppendRequest(String clientMsgId, String text) {}
    record SessionView(String id, String status, String agentVersionId, String routingPlanId) {}
    record TurnView(String turnId, String status, List<PartView> parts) {}
    record PartView(String id, String type, String text, Map<String, Object> payload) {}
}
