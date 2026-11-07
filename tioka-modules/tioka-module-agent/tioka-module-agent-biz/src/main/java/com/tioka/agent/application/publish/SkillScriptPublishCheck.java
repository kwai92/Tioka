package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.skill.api.SkillQueryApi;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** AST-02 #40：含脚本的 Skill 不能启用（沙箱在 M4）。回滚跳过。 */
@Component
@Order(40)
public class SkillScriptPublishCheck implements PublishCheck {
    private final SkillQueryApi skills;

    public SkillScriptPublishCheck(SkillQueryApi skills) {
        this.skills = skills;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        if (ctx.rollback()) {
            return List.of();
        }
        List<PublishError> errors = new ArrayList<>();
        List<AgentSpec.SkillRef> refs = spec.skills() == null ? List.of() : spec.skills();
        for (int i = 0; i < refs.size(); i++) {
            String vid = refs.get(i).skillVersionId();
            var locked = skills.lockVersion(vid);
            if (locked.isPresent() && locked.get().hasScripts()) {
                errors.add(new PublishError("/skills/" + i + "/skillVersionId", "SKILL_SCRIPT_UNSUPPORTED",
                        "含脚本的 Skill 在沙箱就绪前不能启用: " + vid));
            }
        }
        return errors;
    }
}
