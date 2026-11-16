package com.tioka.framework.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundation.starter.core.config.FoundationProperties;
import com.tioka.framework.security.AuthorizationSnapshotSource;
import com.tioka.framework.security.FoundationPermissionEvaluator;
import com.tioka.framework.security.LocalPermissionEvaluator;
import com.tioka.framework.security.PermissionCodeMapper;
import com.tioka.framework.security.PermissionEvaluator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BAS-26 双模式装配入口。
 *
 * <p>由 {@code TiokaServerApplication} 的 {@code scanBasePackages = "com.tioka"} 扫描到。
 * 本地模式注册配置属性与 {@link LocalPermissionEvaluator}；接入模式额外注册
 * {@link FoundationModeGuard} 做启动期校验，并把权限判定切成
 * {@link FoundationPermissionEvaluator}。两个判定器由
 * {@link ConditionalOnFoundationMode} 互斥装配，任何时刻只存在一个。
 *
 * <p>接入模式必须同时提供 {@link AuthorizationSnapshotSource} Bean：没有中台授权数据源就
 * 拒绝启动，避免「配置写着接入中台、运行时却静默按本地权限跑」的半接线状态。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TiokaFoundationProperties.class)
public class FoundationModeConfiguration {

    @Bean
    @ConditionalOnFoundationMode(FoundationMode.FOUNDATION)
    public FoundationModeGuard foundationModeGuard(
            TiokaFoundationProperties properties,
            ObjectProvider<FoundationProperties> foundationProperties) {
        FoundationModeGuard guard = new FoundationModeGuard(properties, foundationProperties);
        guard.validate();
        return guard;
    }

    @Bean
    @ConditionalOnFoundationMode(FoundationMode.LOCAL)
    public PermissionEvaluator localPermissionEvaluator() {
        return new LocalPermissionEvaluator();
    }

    @Bean
    @ConditionalOnFoundationMode(FoundationMode.FOUNDATION)
    public PermissionEvaluator foundationPermissionEvaluator(
            ObjectProvider<AuthorizationSnapshotSource> snapshotSources,
            ObjectProvider<ObjectMapper> objectMappers) {
        AuthorizationSnapshotSource source = snapshotSources.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException(
                    "tioka.foundation.mode=foundation but no AuthorizationSnapshotSource bean is registered; "
                            + "register a Foundation authorization snapshot adapter before enabling this mode");
        }
        ObjectMapper objectMapper = objectMappers.getIfAvailable();
        if (objectMapper == null) {
            throw new IllegalStateException(
                    "tioka.foundation.mode=foundation requires a Spring ObjectMapper bean (ENG-05)");
        }
        return new FoundationPermissionEvaluator(source,
                PermissionCodeMapper.loadDefault(objectMapper));
    }
}
