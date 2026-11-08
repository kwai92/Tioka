package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.AgentSpecValidatorRegistry;
import com.tioka.agent.api.publish.PublishCheck;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布校验器注册表空壳（P3-00b）：收集全部 {@link PublishCheck} Bean，行为与原先 List 注入一致。
 */
@Component
public class DefaultAgentSpecValidatorRegistry implements AgentSpecValidatorRegistry {

    private final List<PublishCheck> validators;

    public DefaultAgentSpecValidatorRegistry(List<PublishCheck> checks) {
        List<PublishCheck> sorted = new ArrayList<>(checks == null ? List.of() : checks);
        AnnotationAwareOrderComparator.sort(sorted);
        this.validators = List.copyOf(sorted);
    }

    @Override
    public List<PublishCheck> validators() {
        return validators;
    }
}
