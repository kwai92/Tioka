package com.tioka.infra.messaging;

import com.tioka.framework.messaging.IdempotentConsumer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {

    /**
     * mode=rocketmq 且 auto-config 已建 {@link RocketMQTemplate}（name-server + producer.group）时启用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "tioka.messaging", name = "mode", havingValue = "rocketmq")
    @ConditionalOnBean(RocketMQTemplate.class)
    MessageTransport rocketMqMessageTransport(RocketMQTemplate rocketMQTemplate) {
        return new RocketMqMessageTransport(rocketMQTemplate);
    }

    /**
     * 兜底：in-memory，或 gate/IT 设了 mode=rocketmq 但尚未具备 Template 时仍保证 OutboxRelay 可装配。
     */
    @Bean
    @ConditionalOnMissingBean(MessageTransport.class)
    MessageTransport inMemoryMessageTransport(List<IdempotentConsumer<?>> consumers,
                                              IdempotentDeliveryService delivery) {
        return new InMemoryMessageTransport(consumers, delivery);
    }
}
