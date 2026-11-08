package com.tioka.infra.api.notify;

/**
 * 通知渠道能力（ADR-029 第 56 条 / NTF-01）。
 */
public record ChannelCapabilities(
        boolean interactive,
        boolean supportsUpdate,
        int maxLength,
        boolean supportsAttachment
) {}
