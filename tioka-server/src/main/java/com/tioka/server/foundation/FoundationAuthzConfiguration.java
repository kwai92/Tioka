package com.tioka.server.foundation;

import com.foundation.starter.client.authz.AuthorizationSnapshotClient;
import com.tioka.framework.foundation.ConditionalOnFoundationMode;
import com.tioka.framework.foundation.FoundationMode;
import com.tioka.framework.security.AuthorizationSnapshotSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接入模式的授权数据源装配（F-02 BAS-26、F4 第一处真实接线）。
 *
 * <p>只在 {@code tioka.foundation.mode=foundation} 时注册 {@link AuthorizationSnapshotSource}；
 * 本地模式一个中台 Bean 都不引入，保持零回归。底座客户端由
 * {@code foundation-starter-client} 在 {@code foundation.client.mode=foundation} 时装配——
 * 两个键由同一个环境变量派生，因此不会出现「应用侧要接中台、starter 却没出站通道」的错配。
 *
 * <p>缺少底座客户端时启动期直接拒绝，而不是让权限判定静默失败或回落到本地权限集。
 */
@Configuration(proxyBeanMethods = false)
public class FoundationAuthzConfiguration {

    @Bean
    @ConditionalOnFoundationMode(FoundationMode.FOUNDATION)
    public AuthorizationSnapshotSource foundationAuthorizationSnapshotSource(
            ObjectProvider<AuthorizationSnapshotClient> clients) {
        AuthorizationSnapshotClient client = clients.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "tioka.foundation.mode=foundation but no AuthorizationSnapshotClient bean is active; "
                            + "check foundation.client.mode and the foundation-starter-client dependency");
        }
        return new FoundationAuthorizationSnapshotSource(client);
    }
}
