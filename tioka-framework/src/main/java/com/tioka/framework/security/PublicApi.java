package com.tioka.framework.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明无需登录的接口（ADR-015）。白名单仅限：登录 / 刷新、OIDC 回调、健康检查、
 * IM 平台回调（以平台签名校验）。{@link #reason()} 必填，便于评审。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicApi {
    String reason();
}
