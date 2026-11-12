package com.tioka.governance.config;

import com.tioka.governance.api.charter.AgentCharterPort;
import com.tioka.governance.api.charter.CharterEventWriter;
import com.tioka.governance.api.charter.CharterTurnInterruptPort;
import com.tioka.governance.application.charter.CharterLifecycleListener;
import com.tioka.governance.application.charter.CharterLifecycleService;
import com.tioka.governance.application.charter.DefaultCharterEventWriter;
import com.tioka.governance.application.charter.IdentityCharterConsumer;
import com.tioka.governance.infrastructure.charter.TrustCharterEventRepository;
import com.tioka.governance.infrastructure.mapper.TrustCharterEventMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * TRU-01 授权书基础设施。默认关闭：{@code tioka.trust.charter.enabled=true} 才装配。
 * 发布校验在 agent-biz {@code CharterPublishCheck}；本配置装配事件写入、TRU-03 状态机
 * （{@code CharterLifecycleService}）与身份事件消费（MQ 主口径 + 进程内加速）。
 *
 * <p>端口实现来自其他模块：{@code AgentCharterPort}（agent-biz）、
 * {@code CharterTurnInterruptPort}（runtime-biz），缺失（无 server 装配的离线单测）
 * 时生命周期相关 bean 不创建。
 */
@AutoConfiguration
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.trust.charter", name = "enabled", havingValue = "true")
public class CharterAutoConfiguration {

    @Bean
    TrustCharterEventRepository trustCharterEventRepository(TrustCharterEventMapper mapper) {
        return new TrustCharterEventRepository(mapper);
    }

    @Bean
    CharterEventWriter charterEventWriter(TrustCharterEventRepository events) {
        return new DefaultCharterEventWriter(events);
    }

    @Bean
    @ConditionalOnBean({AgentCharterPort.class, CharterTurnInterruptPort.class})
    CharterLifecycleService charterLifecycleService(AgentCharterPort agents,
                                                    CharterTurnInterruptPort interrupts,
                                                    CharterEventWriter events,
                                                    com.tioka.framework.security.PrincipalHolder principals) {
        return new CharterLifecycleService(agents, interrupts, events, principals);
    }

    @Bean
    @ConditionalOnBean(CharterLifecycleService.class)
    IdentityCharterConsumer identityCharterConsumer(CharterLifecycleService lifecycle) {
        return new IdentityCharterConsumer(lifecycle);
    }

    @Bean
    @ConditionalOnBean(CharterLifecycleService.class)
    CharterLifecycleListener charterLifecycleListener(CharterLifecycleService lifecycle) {
        return new CharterLifecycleListener(lifecycle);
    }
}

