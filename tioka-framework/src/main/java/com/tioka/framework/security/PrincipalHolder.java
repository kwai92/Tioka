package com.tioka.framework.security;

import java.util.Optional;
import java.util.function.Supplier;

/** 当前 Principal 的读取与切换（ADR-015）。替代以 Long 存 ID 的 TenantContext。 */
public interface PrincipalHolder {

    /** 当前主体；没有时抛出 {@code UNAUTHORIZED}。 */
    Principal current();

    Optional<Principal> find();

    /** 以给定身份执行，后台任务用它恢复 run_as；结束后恢复原身份。 */
    <T> T runAs(Principal principal, Supplier<T> body);

    default void runAs(Principal principal, Runnable body) {
        runAs(principal, () -> {
            body.run();
            return null;
        });
    }
}
