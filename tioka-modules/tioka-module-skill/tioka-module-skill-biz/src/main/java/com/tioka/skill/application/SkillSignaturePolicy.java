package com.tioka.skill.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SkillSignaturePolicy {
    private final boolean force;

    public SkillSignaturePolicy(@Value("${tioka.skill.force-signature:false}") boolean force) {
        this.force = force;
    }

    public void validate(boolean signed) {
        if (force && !signed) {
            throw new BizException(ErrorCode.FORBIDDEN, "未签名 Skill 被拒绝");
        }
    }
}
