package com.tioka.infra.messaging;

import com.tioka.framework.messaging.IdempotentConsumer;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RocketMQ 监听器适配器：按 {@link IdempotentConsumer} 建推送消费者，
 * 去重 / runAs / 死信由 {@link IdempotentDeliveryService} 处理。
 *
 * <p>不使用 {@code @RocketMQMessageListener}，避免 ArchUnit「须继承 IdempotentConsumer」误伤适配器本身。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.messaging", name = "mode", havingValue = "rocketmq")
public class RocketMqConsumerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumerBootstrap.class);

    private final List<IdempotentConsumer<?>> consumers;
    private final IdempotentDeliveryService delivery;
    private final MessagingProperties props;
    private final String nameServer;
    private final List<DefaultMQPushConsumer> started = new ArrayList<>();

    public RocketMqConsumerBootstrap(List<IdempotentConsumer<?>> consumers,
                                     IdempotentDeliveryService delivery,
                                     MessagingProperties props,
                                     @Value("${rocketmq.name-server:}") String nameServer) {
        this.consumers = consumers;
        this.delivery = delivery;
        this.props = props;
        this.nameServer = nameServer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        if (nameServer == null || nameServer.isBlank()) {
            log.warn("rocketmq.name-server 未配置，跳过消费者启动");
            return;
        }
        for (IdempotentConsumer<?> consumer : consumers) {
            String topic = consumer.resolvedTopic();
            if (topic == null || topic.isBlank()) {
                log.warn("消费者 {} 未声明 topic()，跳过 RocketMQ 订阅", consumer.resolvedConsumerName());
                continue;
            }
            DefaultMQPushConsumer push = new DefaultMQPushConsumer(consumer.resolvedConsumerName());
            push.setNamesrvAddr(nameServer);
            push.setMaxReconsumeTimes(props.getConsumer().getMaxAttempts());
            String tag = consumer.resolvedTagExpression();
            push.subscribe(topic, tag == null || tag.isBlank() ? "*" : tag);
            push.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                    try {
                        delivery.deliver(
                                consumer.resolvedConsumerName(),
                                msg.getTopic(),
                                msg.getTags() == null ? "*" : msg.getTags(),
                                body,
                                msg.getReconsumeTimes());
                    } catch (RuntimeException e) {
                        log.warn("RocketMQ 消费失败 consumer={} msgId={}: {}",
                                consumer.resolvedConsumerName(), msg.getMsgId(), e.toString());
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                }
                return ConsumeOrderlyStatus.SUCCESS;
            });
            push.start();
            started.add(push);
            log.info("RocketMQ 消费者已启动 group={} topic={} tag={}",
                    consumer.resolvedConsumerName(), topic, tag);
        }
    }

    @PreDestroy
    void stop() {
        for (DefaultMQPushConsumer c : started) {
            try {
                c.shutdown();
            } catch (Exception e) {
                log.warn("关闭 RocketMQ 消费者失败: {}", e.toString());
            }
        }
        started.clear();
    }
}
