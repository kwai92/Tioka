package com.tioka.governance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.governance.application.config.ConfigCurrentPointerStore;
import com.tioka.governance.application.config.ConfigProjectionCache;
import com.tioka.governance.application.config.ConfigSpecNormalizer;
import com.tioka.governance.application.config.ConfigVersionReconciler;
import com.tioka.governance.application.config.ConfigVersionRefreshBus;
import com.tioka.governance.application.config.DefaultConfigVersionService;
import com.tioka.governance.infrastructure.config.CfgStore;
import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.mapper.CfgDraftMapper;
import com.tioka.governance.infrastructure.mapper.CfgProjectionPointerMapper;
import com.tioka.governance.infrastructure.mapper.CfgVersionMapper;
import com.tioka.system.api.audit.AuditApi;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GOV-12 配置版本框架。默认关闭：{@code tioka.config.version.enabled=true} 才装配。
 */
@AutoConfiguration
@EnableScheduling
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.config.version", name = "enabled", havingValue = "true")
public class GovernanceAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "tioka.config.version")
    ConfigVersioningProperties configVersioningProperties() {
        return new ConfigVersioningProperties();
    }

    @Bean
    CfgStore cfgStore(
            CfgDraftMapper drafts,
            CfgVersionMapper versions,
            CfgChangeMapper changes,
            CfgProjectionPointerMapper projections) {
        return new CfgStore(drafts, versions, changes, projections);
    }

    @Bean
    ConfigSpecNormalizer configSpecNormalizer(ObjectMapper objectMapper) {
        return new ConfigSpecNormalizer(objectMapper);
    }

    @Bean
    ConfigProjectionCache configProjectionCache() {
        return new ConfigProjectionCache();
    }

    @Bean
    ConfigCurrentPointerStore configCurrentPointerStore(StringRedisTemplate redis) {
        return new ConfigCurrentPointerStore(redis);
    }

    @Bean
    @ConditionalOnMissingBean(RedisMessageListenerContainer.class)
    RedisMessageListenerContainer configRedisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    @Bean
    ConfigVersionRefreshBus configVersionRefreshBus(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            RedisMessageListenerContainer container,
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            CfgStore store,
            ConfigSpecNormalizer normalizer) {
        return new ConfigVersionRefreshBus(redis, objectMapper, container, cache, pointers, store, normalizer);
    }

    @Bean
    ConfigVersionReconciler configVersionReconciler(
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            ConfigVersionRefreshBus bus) {
        return new ConfigVersionReconciler(cache, pointers, bus);
    }

    @Bean
    ConfigVersionApi configVersionApi(
            CfgStore store,
            ConfigSpecNormalizer normalizer,
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            ConfigVersionRefreshBus bus,
            ObjectMapper objectMapper,
            ObjectProvider<AuditApi> auditApi) {
        return new DefaultConfigVersionService(
                store, normalizer, cache, pointers, bus, objectMapper, auditApi);
    }
}
