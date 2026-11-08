package com.tioka.infra.api.notify;

/**
 * 投递引用（渠道侧 message id 等）。
 */
public record DeliveryRef(String channel, String externalRef) {}
