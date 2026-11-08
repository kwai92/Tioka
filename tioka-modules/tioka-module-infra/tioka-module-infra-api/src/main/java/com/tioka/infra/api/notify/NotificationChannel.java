package com.tioka.infra.api.notify;

/**
 * 通知渠道 SPI（ADR-029 第 56 条 / NTF-01）。实现在 infra-biz。
 */
public interface NotificationChannel {

    String channel();

    ChannelCapabilities capabilities();

    DeliveryRef send(Recipient to, Notice notice);

    default void update(DeliveryRef ref, Notice notice) {
        throw new UnsupportedOperationException("channel does not support update: " + channel());
    }
}
