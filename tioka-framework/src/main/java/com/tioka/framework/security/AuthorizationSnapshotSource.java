package com.tioka.framework.security;

import java.util.Set;

/**
 * 中台授权快照的入站端口（能力归属清单「身份上下文适配」）。
 *
 * <p>Tioka 框架层只声明「按人员 ID 取中台原始权限项」这一能力，不绑定任何具体中台 SDK。
 * 具体适配器由应用装配层注册：把 Foundation 授权快照的 {@code permissions} 原样交出，
 * 转换与判定统一交给 {@link PermissionCodeMapper} 与 {@link FoundationPermissionEvaluator}。
 *
 * <p><b>契约</b>：返回空集合表示「已成功查询且确实无权限」；取不到数据必须返回 {@code null}
 * 或抛异常，调用方一律 fail-closed，绝不回落到应用本地的权限集。
 */
@FunctionalInterface
public interface AuthorizationSnapshotSource {

    /** 取指定人员在当前应用下的中台原始权限项；不可用返回 {@code null} 或抛异常。 */
    Set<String> permissionsFor(String personId);
}
