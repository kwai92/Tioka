package com.tioka.infra.messaging;

import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;

/**
 * Outbox 中继投递通道：RocketMQ 或进程内总线。
 */
public interface MessageTransport {

    void send(SysOutboxDO row);
}
