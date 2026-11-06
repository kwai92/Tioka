package com.tioka.agent.api.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置快照（M1 子集，schemaVersion=1）。预留字段 M1 必须为 null。
 */
public record AgentSpec(
        int schemaVersion,
        Profile profile,
        Instructions instructions,
        ModelPolicy model,
        ToolSet tools,
        List<SkillRef> skills,
        KnowledgeBinding knowledge,
        SandboxPolicy sandbox,
        ApprovalSettings approval,
        Limits limits,
        JsonNode charter,
        JsonNode output,
        JsonNode subAgents,
        String dataClearance
) {
    public AgentSpec {
        if (skills == null) skills = List.of();
        if (tools == null) tools = ToolSet.empty();
        if (knowledge == null) knowledge = KnowledgeBinding.empty();
        if (sandbox == null) sandbox = SandboxPolicy.defaults();
        if (approval == null) approval = ApprovalSettings.empty();
        if (limits == null) limits = Limits.defaults();
    }

    public record Profile(String name, String description, String icon, List<String> tags, String kind) {
        public Profile {
            if (tags == null) tags = List.of();
            if (kind == null || kind.isBlank()) kind = "CHAT";
        }
    }

    public record Instructions(List<Section> sections) {
        public Instructions {
            if (sections == null) sections = List.of();
        }

        public record Section(String key, String text) {}
    }

    public record ModelPolicy(String routingPlanId, boolean allowUserOverride) {
        public ModelPolicy(String routingPlanId) {
            this(routingPlanId, false);
        }
    }

    public record ToolSet(List<String> builtin, List<McpBinding> mcp, List<Object> flows, List<Object> business) {
        public ToolSet {
            if (builtin == null) builtin = List.of();
            if (mcp == null) mcp = List.of();
            if (flows == null) flows = List.of();
            if (business == null) business = List.of();
        }

        public static ToolSet empty() {
            return new ToolSet(List.of(), List.of(), List.of(), List.of());
        }

        public record McpBinding(String serverId, List<String> allowlist) {
            public McpBinding {
                if (allowlist == null) allowlist = List.of();
            }
        }
    }

    public record SkillRef(String skillVersionId, String mode) {
        public SkillRef {
            if (mode == null || mode.isBlank()) mode = "ALWAYS";
        }
    }

    public record KnowledgeBinding(List<String> kbIds, String mode) {
        public KnowledgeBinding {
            if (kbIds == null) kbIds = List.of();
            if (mode == null || mode.isBlank()) mode = "TOOL";
        }

        public static KnowledgeBinding empty() {
            return new KnowledgeBinding(List.of(), "TOOL");
        }
    }

    public record SandboxPolicy(String level, List<String> networkAllowlist) {
        public SandboxPolicy {
            if (level == null || level.isBlank()) level = "EDIT";
            if (networkAllowlist == null) networkAllowlist = List.of();
        }

        public static SandboxPolicy defaults() {
            return new SandboxPolicy("EDIT", List.of());
        }
    }

    public record ApprovalSettings(Map<String, String> toolPolicies, String assignee) {
        public ApprovalSettings {
            if (toolPolicies == null) toolPolicies = Map.of();
        }

        public static ApprovalSettings empty() {
            return new ApprovalSettings(Map.of(), null);
        }
    }

    public record Limits(Integer maxSteps, Integer maxTokensPerTurn) {
        public Limits {
            if (maxSteps == null || maxSteps < 1) maxSteps = 20;
        }

        public static Limits defaults() {
            return new Limits(20, null);
        }
    }
}
