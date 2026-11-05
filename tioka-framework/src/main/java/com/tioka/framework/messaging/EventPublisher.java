package com.tioka.framework.messaging;

/**
 * 跨域事件发布（ORC-02，ADR-020，施工设计 M2-01）。
 *
 * <p>实现写入 {@code sys_outbox}，必须在业务事务内调用（Propagation.MANDATORY），由中继投递到 RocketMQ。
 * 信封格式见 {@code docs/contracts/events/mq-envelope-v1.json}。
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
