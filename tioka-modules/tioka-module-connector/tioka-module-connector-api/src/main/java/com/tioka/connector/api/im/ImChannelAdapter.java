package com.tioka.connector.api.im;

/**
 * IM 渠道 SPI（ADR-021 CON-06）。替代 system-api 遗留的同名接口；
 * 既有 Feishu/钉钉/企微适配器仍实现 system-api，M2 入站包逐步迁到本接口。
 */
public interface ImChannelAdapter {

    /** {@code feishu} / {@code dingtalk} / {@code wecom} / {@code custom:{name}} */
    String channel();

    InboundMessage verifyAndParse(InboundRequest request);

    CardAction parseCardAction(InboundRequest request);

    OutboundRef send(ImTarget target, ImMessage message);

    void update(OutboundRef ref, ImMessage message);

    ExternalUser lookupUser(String externalUserId);
}
