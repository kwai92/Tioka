package com.tioka.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.infra.api.notify.NotificationChannel;
import com.tioka.infra.application.notify.NoopTestChannel;
import com.tioka.infra.application.notify.NotificationChannelRegistry;
import com.tioka.infra.application.notify.NotificationPortBridge;
import com.tioka.infra.application.notify.NotificationService;
import com.tioka.infra.application.notify.NtfDeliveryAlertHook;
import com.tioka.infra.application.notify.NtfDeliveryConsumer;
import com.tioka.infra.application.notify.RecipientResolver;
import com.tioka.infra.infrastructure.NtfDeliveryRepository;
import com.tioka.infra.infrastructure.SysExternalIdentityRepository;
import com.tioka.infra.infrastructure.mapper.NtfDeliveryMapper;
import com.tioka.infra.infrastructure.mapper.SysExternalIdentityMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.List;

/**
 * NTF-01 通知渠道平台装配。默认关闭：{@code tioka.notify.enabled=true} 才注册。
 * 注册投递仓储 / 服务 / 消费者（IdempotentConsumer，经 infra 投递适配器收集）/
 * 收件人解析 / 渠道注册表；NoopTestChannel（{@code tioka.notify.noop-channel.enabled}）
 * 与 M2 NotificationPort 桥接（{@code tioka.notify.bridge.enabled}）独立开关。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.notify", name = "enabled", havingValue = "true")
public class InfraNotifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    NtfDeliveryRepository ntfDeliveryRepository(NtfDeliveryMapper mapper) {
        return new NtfDeliveryRepository(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    SysExternalIdentityRepository sysExternalIdentityRepository(SysExternalIdentityMapper mapper) {
        return new SysExternalIdentityRepository(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    RecipientResolver recipientResolver(SysExternalIdentityRepository identities) {
        return new RecipientResolver(identities);
    }

    /** 渠道注册表（F35）：渠道实现以 Spring Bean 注入，调用方只认渠道代码；无渠道时为空表。 */
    @Bean
    @ConditionalOnMissingBean
    NotificationChannelRegistry notificationChannelRegistry(ObjectProvider<NotificationChannel> channelList) {
        return new NotificationChannelRegistry(channelList.stream().toList());
    }

    /** 空测试渠道：仅测试 / 联调（缺省关闭，测试 profile 打开）。 */
    @Bean
    @ConditionalOnMissingBean(NoopTestChannel.class)
    @ConditionalOnProperty(prefix = "tioka.notify.noop-channel", name = "enabled", havingValue = "true")
    NotificationChannel noopTestChannel() {
        return new NoopTestChannel();
    }

    @Bean
    @ConditionalOnMissingBean
    NotificationService notificationService(NtfDeliveryRepository deliveries,
                                            EventPublisher publisher,
                                            ObjectMapper mapper) {
        return new NotificationService(deliveries, publisher, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    NtfDeliveryConsumer ntfDeliveryConsumer(NtfDeliveryRepository deliveries,
                                            RecipientResolver resolver,
                                            NotificationChannelRegistry registry,
                                            ObjectProvider<com.tioka.governance.api.label.SinkGuard> sinkGuard,
                                            ObjectProvider<NtfDeliveryAlertHook> alertHook,
                                            ObjectMapper mapper,
                                            PlatformTransactionManager txManager) {
        return new NtfDeliveryConsumer(deliveries, resolver, registry, sinkGuard, alertHook,
                mapper, txManager);
    }

    /**
     * M2 NotificationPort 桥接（缺省关闭）：启用后 RulePauseService / QuotaWarnNotifier
     * 的通知真实入队（当前端口缺席静默跳过，行为会变），channels 缺省 noop 占位。
     */
    @Bean
    @ConditionalOnMissingBean(com.tioka.system.api.notify.NotificationPort.class)
    @ConditionalOnProperty(prefix = "tioka.notify.bridge", name = "enabled", havingValue = "true")
    NotificationPortBridge notificationPortBridge(NotificationService service,
            @Value("${tioka.notify.bridge.channels:noop}") String channels) {
        List<String> list = Arrays.stream(channels.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        return new NotificationPortBridge(service, list);
    }
}
