package com.tioka.framework.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 接入模式的权限判定：以 Foundation 中台授权快照为唯一授权来源。
 *
 * <p>三条不可放宽的逻辑约束：
 *
 * <ol>
 *   <li><b>不回落本地</b>：本实现从不读取当前主体的 {@code permissions} 字段。
 *       若在中台不可用时回落到应用自建权限集，等于本地残留授权绕过中台；</li>
 *   <li><b>fail-closed</b>：数据源返回 {@code null} 或抛异常时，按「缺少第一个声明权限」
 *       处理，由切面走既有拒绝审计与 403，绝不静默放行；</li>
 *   <li><b>先映射后判定</b>：中台权限项经 {@link PermissionCodeMapper} 转成 Tioka
 *       具体权限码后才参与比较，中台通配码不提升为 Tioka 超管。</li>
 * </ol>
 *
 * <p>另外，注解声明的权限码若未登记在映射契约中，一律判为缺失：
 * 拼错的权限码既不该意外放行，也不该被当作「中台没有这项」而绕开校验。
 */
public final class FoundationPermissionEvaluator implements PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FoundationPermissionEvaluator.class);

    private final AuthorizationSnapshotSource source;
    private final PermissionCodeMapper mapper;

    public FoundationPermissionEvaluator(AuthorizationSnapshotSource source,
                                         PermissionCodeMapper mapper) {
        if (source == null) {
            throw new IllegalArgumentException("AuthorizationSnapshotSource is required");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("PermissionCodeMapper is required");
        }
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public String firstMissing(Principal principal, String[] required) {
        if (required.length == 0) {
            return null;
        }
        for (String code : required) {
            if (!mapper.isRegistered(code)) {
                return code;
            }
        }
        Set<String> raw;
        try {
            raw = source.permissionsFor(principal.userId());
        } catch (RuntimeException ex) {
            log.warn("中台授权快照不可用，拒绝放行：personId={}, cause={}",
                    principal.userId(), ex.toString());
            return required[0];
        }
        if (raw == null) {
            log.warn("中台授权快照返回空数据，拒绝放行：personId={}", principal.userId());
            return required[0];
        }
        boolean superAdmin = mapper.grantsSuperAdmin(raw);
        Set<String> effective = mapper.map(raw);
        for (String code : required) {
            if (!superAdmin && !effective.contains(code)) {
                return code;
            }
        }
        return null;
    }
}
