package com.tioka.framework.foundation;

import com.foundation.starter.core.config.FoundationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BAS-26 双模式装配入口。
 *
 * <p>由 {@code TiokaServerApplication} 的 {@code scanBasePackages = "com.tioka"} 扫描到。
 * 本地模式只注册配置属性 Bean；接入模式额外注册 {@link FoundationModeGuard} 做启动期校验。
 * BAS-20 接线时，各「本地实现 / Foundation 实现」可继续用
 * {@link ConditionalOnFoundationMode} 在此分叉，避免两套并存。
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
}
