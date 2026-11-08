package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.AgentSpecValidatorRegistry;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.publish.PublishValidator;
import com.tioka.agent.api.spec.AgentSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-01/02：经 {@link AgentSpecValidatorRegistry} 执行已注册校验器（P3-00b 起）。
 */
@Component
public class DefaultPublishValidator implements PublishValidator {
    private final AgentSpecValidatorRegistry registry;

    public DefaultPublishValidator(AgentSpecValidatorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<PublishCheck.PublishError> validate(AgentSpec spec, PublishCheck.PublishContext ctx) {
        List<PublishCheck.PublishError> errors = new ArrayList<>();
        for (PublishCheck check : registry.validators()) {
            errors.addAll(check.check(spec, ctx));
        }
        return errors;
    }
}
