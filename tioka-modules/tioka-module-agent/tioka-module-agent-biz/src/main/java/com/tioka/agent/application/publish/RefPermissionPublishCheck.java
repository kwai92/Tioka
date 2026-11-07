package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.ResourceGuard;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-02 #30：发布者对每个引用有 USE；路由方案要求 model:read。回滚也执行。
 */
@Component
@Order(30)
public class RefPermissionPublishCheck implements PublishCheck {
    private final ResourceGuard resourceGuard;
    private final PrincipalHolder principals;

    public RefPermissionPublishCheck(ResourceGuard resourceGuard, PrincipalHolder principals) {
        this.resourceGuard = resourceGuard;
        this.principals = principals;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        List<PublishError> errors = new ArrayList<>();
        Principal publisher = principals.current();
        if (!publisher.hasPermission(Permissions.MODEL_READ)) {
            errors.add(new PublishError("/model/routingPlanId", "REF_FORBIDDEN",
                    "发布者缺少 model:read"));
        }

        List<AgentSpec.ToolSet.McpBinding> mcp = spec.tools() == null ? List.of() : spec.tools().mcp();
        for (int i = 0; i < mcp.size(); i++) {
            String serverId = mcp.get(i).serverId();
            if (serverId == null || serverId.isBlank()) {
                continue;
            }
            if (!resourceGuard.allows("mcp", serverId, AccessLevel.USE)) {
                errors.add(new PublishError("/tools/mcp/" + i + "/serverId", "REF_FORBIDDEN",
                        "发布者对 MCP Server 无 USE: " + serverId));
            }
        }

        List<String> kbIds = spec.knowledge() == null ? List.of() : spec.knowledge().kbIds();
        for (int i = 0; i < kbIds.size(); i++) {
            String kbId = kbIds.get(i);
            if (!resourceGuard.allows("kb", kbId, AccessLevel.USE)) {
                errors.add(new PublishError("/knowledge/kbIds/" + i, "REF_FORBIDDEN",
                        "发布者对知识库无 USE: " + kbId));
            }
        }

        List<AgentSpec.SkillRef> skillRefs = spec.skills() == null ? List.of() : spec.skills();
        for (int i = 0; i < skillRefs.size(); i++) {
            String vid = skillRefs.get(i).skillVersionId();
            if (vid == null || vid.isBlank()) {
                continue;
            }
            if (!resourceGuard.allows("skill", vid, AccessLevel.USE)) {
                errors.add(new PublishError("/skills/" + i + "/skillVersionId", "REF_FORBIDDEN",
                        "发布者对 Skill 无 USE: " + vid));
            }
        }
        return errors;
    }
}
