package com.tioka.system.api.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在应用服务的写操作上，由切面写审计（ADR-017 / GOV-02）。
 *
 * <p>{@link #resourceIdArg()} 为 SpEL，从方法参数取资源 ID，如 {@code #cmd.agentId}。
 * {@link #include()} 为写入 {@code details_json} 的参数字段白名单；空则不记请求体。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {
    String action();

    String resourceType() default "";

    String resourceIdArg() default "";

    /** details_json 字段白名单；空表示不记录参数摘要。 */
    String[] include() default {};
}
