package com.tioka.framework.foundation;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/** {@link ConditionalOnFoundationMode} 的条件实现：直接读环境属性，不依赖配置 Bean 已创建。 */
class OnFoundationModeCondition implements Condition {

    private static final String MODE_PROPERTY = "tioka.foundation.mode";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnFoundationMode.class.getName());
        if (attributes == null) {
            return false;
        }
        Object raw = attributes.get("value");
        FoundationMode required = raw instanceof FoundationMode mode
                ? mode
                : FoundationMode.parse(String.valueOf(raw));
        Environment environment = context.getEnvironment();
        return FoundationMode.parse(environment.getProperty(MODE_PROPERTY, "local")) == required;
    }
}
