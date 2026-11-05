package com.tioka.framework.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能权限声明（ADR-015）。列出多个权限点时需全部具备。
 *
 * <p>所有 Controller 方法必须带本注解或 {@link PublicApi}，由 ArchUnit 检查（ENG-10）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {
    String[] value();
}
