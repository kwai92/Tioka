package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.publish.PublishValidator;
import com.tioka.agent.api.spec.AgentSpec;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-01：可注入空检查列表，发布可通。AST-02 追加 {@link PublishCheck} Bean。
 */
@Component
public class DefaultPublishValidator implements PublishValidator {
    private final List<PublishCheck> checks;

    public DefaultPublishValidator(List<PublishCheck> checks) {
        List<PublishCheck> sorted = new ArrayList<>(checks == null ? List.of() : checks);
        AnnotationAwareOrderComparator.sort(sorted);
        this.checks = List.copyOf(sorted);
    }

    @Override
    public List<PublishCheck.PublishError> validate(AgentSpec spec, PublishCheck.PublishContext ctx) {
        List<PublishCheck.PublishError> errors = new ArrayList<>();
        for (PublishCheck check : checks) {
            errors.addAll(check.check(spec, ctx));
        }
        return errors;
    }
}
