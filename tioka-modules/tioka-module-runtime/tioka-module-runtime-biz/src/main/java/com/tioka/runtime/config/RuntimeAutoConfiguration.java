package com.tioka.runtime.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ComponentScan("com.tioka.runtime")
@EnableConfigurationProperties({KernelProperties.class, ObservabilityProperties.class})
public class RuntimeAutoConfiguration {

    /**
     * Redis 发布订阅容器（RT-05 跨实例信号）。
     *
     * <p><b>标记 {@code @Primary}</b>：{@code GovernanceAutoConfiguration} 也定义了一个等价的
     * {@code configRedisMessageListenerContainer}（带 {@code @ConditionalOnMissingBean}，本意是复用现成的）。
     * 但两个配置类都在 {@code com.tioka} 包下、被主应用 {@code scanBasePackages="com.tioka"} 当普通
     * {@code @Configuration} 组件扫描，注册顺序不确定，{@code @ConditionalOnMissingBean} 因而失效，
     * 开启 {@code config.version.enabled=true}（gate profile）时两个容器并存，
     * {@code SignalBus} / {@code ConfigVersionRefreshBus} 按类型注入报 {@code found 2}。
     * 两容器完全等价（{@code new} + {@code setConnectionFactory}），共享一个即可；
     * 本容器无条件存在，故设为 primary，使按类型注入确定性地落到它，与扫描顺序无关。
     */
    @Bean
    @Primary
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
