package com.tioka.server.foundation;

import com.foundation.starter.client.authz.AuthorizationSnapshotClient;
import com.tioka.framework.security.AuthorizationSnapshotSource;

import java.util.Set;

/**
 * 应用装配层：把底座的授权快照客户端适配成框架层的入站端口。
 *
 * <p>刻意只做转发，不承担任何授权语义：不解析也不改写权限码、不缓存快照、
 * 不读取当前主体的本地权限集。原始权限项交给 {@code PermissionCodeMapper} 映射后，
 * 才由 {@code FoundationPermissionEvaluator} 判定；数据源返回 null 或抛异常时，
 * 判定器按 fail-closed 拒绝放行。
 *
 * <p>本类不引 {@code foundation-starter-security}：那是服务端安全 starter，会装配
 * 认证过滤器并与 Tioka 本地认证链路冲突。接入方只需要底座的只读客户端。
 */
public final class FoundationAuthorizationSnapshotSource implements AuthorizationSnapshotSource {

    private final AuthorizationSnapshotClient client;

    public FoundationAuthorizationSnapshotSource(AuthorizationSnapshotClient client) {
        if (client == null) {
            throw new IllegalArgumentException("AuthorizationSnapshotClient is required");
        }
        this.client = client;
    }

    @Override
    public Set<String> permissionsFor(String personId) {
        return client.permissionsFor(personId);
    }
}
