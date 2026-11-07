package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.BuiltinToolCatalog;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.knowledge.api.KnowledgeQueryApi;
import com.tioka.mcp.api.McpServerQueryApi;
import com.tioka.model.api.RoutingPlanQueryApi;
import com.tioka.skill.api.SkillQueryApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-02 #20：引用存在与可用。回滚也执行。
 */
@Component
@Order(20)
public class RefExistsPublishCheck implements PublishCheck {
    private final RoutingPlanQueryApi routingPlans;
    private final McpServerQueryApi mcpServers;
    private final KnowledgeQueryApi knowledge;
    private final SkillQueryApi skills;
    private final ObjectProvider<BuiltinToolCatalog> builtins;

    public RefExistsPublishCheck(RoutingPlanQueryApi routingPlans,
                                 McpServerQueryApi mcpServers,
                                 KnowledgeQueryApi knowledge,
                                 SkillQueryApi skills,
                                 ObjectProvider<BuiltinToolCatalog> builtins) {
        this.routingPlans = routingPlans;
        this.mcpServers = mcpServers;
        this.knowledge = knowledge;
        this.skills = skills;
        this.builtins = builtins;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        List<PublishError> errors = new ArrayList<>();
        String planId = spec.model() == null ? null : spec.model().routingPlanId();
        RoutingPlanQueryApi.ChatAvailability avail = routingPlans.lockForChat(planId);
        switch (avail) {
            case MISSING -> errors.add(new PublishError("/model/routingPlanId", "REF_NOT_FOUND",
                    "路由方案不存在: " + planId));
            case UNPUBLISHED -> errors.add(new PublishError("/model/routingPlanId", "REF_UNAVAILABLE",
                    "路由方案未发布或不可用于 CHAT: " + planId));
            case AVAILABLE -> { /* ok */ }
        }

        List<AgentSpec.ToolSet.McpBinding> mcp = spec.tools() == null ? List.of() : spec.tools().mcp();
        for (int i = 0; i < mcp.size(); i++) {
            AgentSpec.ToolSet.McpBinding b = mcp.get(i);
            String path = "/tools/mcp/" + i;
            var ref = mcpServers.lockServer(b.serverId());
            if (ref.isEmpty()) {
                errors.add(new PublishError(path + "/serverId", "REF_NOT_FOUND",
                        "MCP Server 不存在: " + b.serverId()));
                continue;
            }
            if (ref.get().circuitOpen()) {
                errors.add(new PublishError(path + "/serverId", "REF_UNAVAILABLE",
                        "MCP Server 已熔断: " + b.serverId()));
            }
            for (int j = 0; j < b.allowlist().size(); j++) {
                String tool = b.allowlist().get(j);
                if (!mcpServers.hasTool(b.serverId(), tool)) {
                    errors.add(new PublishError(path + "/allowlist/" + j, "REF_NOT_FOUND",
                            "MCP 工具不存在: " + b.serverId() + "/" + tool));
                }
            }
        }

        List<String> kbIds = spec.knowledge() == null ? List.of() : spec.knowledge().kbIds();
        for (int i = 0; i < kbIds.size(); i++) {
            String kbId = kbIds.get(i);
            if (!knowledge.lockExists(kbId)) {
                errors.add(new PublishError("/knowledge/kbIds/" + i, "REF_NOT_FOUND",
                        "知识库不存在或已删除: " + kbId));
            }
        }

        List<AgentSpec.SkillRef> skillRefs = spec.skills() == null ? List.of() : spec.skills();
        for (int i = 0; i < skillRefs.size(); i++) {
            String vid = skillRefs.get(i).skillVersionId();
            if (skills.lockVersion(vid).isEmpty()) {
                errors.add(new PublishError("/skills/" + i + "/skillVersionId", "REF_NOT_FOUND",
                        "Skill 版本不存在: " + vid));
            }
        }

        BuiltinToolCatalog catalog = builtins.getIfAvailable();
        List<String> builtin = spec.tools() == null ? List.of() : spec.tools().builtin();
        for (int i = 0; i < builtin.size(); i++) {
            String name = builtin.get(i);
            if (catalog == null || !catalog.contains(name)) {
                errors.add(new PublishError("/tools/builtin/" + i, "REF_NOT_FOUND",
                        "内置工具不在注册表: " + name));
            }
        }
        return errors;
    }
}
