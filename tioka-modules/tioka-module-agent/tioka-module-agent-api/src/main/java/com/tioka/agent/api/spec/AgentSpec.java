package com.tioka.agent.api.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置快照（schemaVersion=1）。
 * M3（P3-00b / K9）：一次加齐可空段 {@code output}、{@code charter}、{@code prompt}、
 * {@code context}、{@code memory}、{@code execution}；缺省 null = 行为等于现状。
 * M3 DAT-01 解冻：{@code datasets}（绑定的数据集名列表）、{@code dataClearance}（读标签上限，
 * 缺省 INTERNAL）；{@code subAgents} 仍为预留（须 null，直至对应工作项解冻）。
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
        OutputContract output,
        PromptRef prompt,
        ContextSettings context,
        MemorySettings memory,
        ExecutionSettings execution,
        JsonNode subAgents,
        String dataClearance,
        List<String> datasets
) {
        public AgentSpec {
            if (skills == null) skills = List.of();
            if (tools == null) tools = ToolSet.empty();
            if (knowledge == null) knowledge = KnowledgeBinding.empty();
            if (sandbox == null) sandbox = SandboxPolicy.defaults();
            if (approval == null) approval = ApprovalSettings.empty();
            if (limits == null) limits = Limits.defaults();
            if (datasets == null) datasets = List.of();
            else datasets = List.copyOf(datasets);
        }

        /** 兼容 18 参口径（DAT-01 解冻前）；datasets = 空列表。 */
        public AgentSpec(int schemaVersion, Profile profile, Instructions instructions,
                         ModelPolicy model, ToolSet tools, List<SkillRef> skills,
                         KnowledgeBinding knowledge, SandboxPolicy sandbox,
                         ApprovalSettings approval, Limits limits, JsonNode charter,
                         OutputContract output, PromptRef prompt, ContextSettings context,
                         MemorySettings memory, ExecutionSettings execution,
                         JsonNode subAgents, String dataClearance) {
            this(schemaVersion, profile, instructions, model, tools, skills, knowledge, sandbox,
                    approval, limits, charter, output, prompt, context, memory, execution,
                    subAgents, dataClearance, List.of());
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
