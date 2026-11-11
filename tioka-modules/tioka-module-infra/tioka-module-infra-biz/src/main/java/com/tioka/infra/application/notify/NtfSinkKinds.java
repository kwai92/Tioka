package com.tioka.infra.application.notify;

import com.tioka.governance.api.label.SinkKind;

import java.util.Locale;

/**
 * NTF 渠道 → 去向种类映射（ADR-040 / DAT-02）：feishu→IM、email→MAIL；
 * 未知渠道按 IM 缺省（内部沟通口径，与 {@code tioka.governance.label-sink-defaults.IM} 对齐）。
 */
public final class NtfSinkKinds {

    private NtfSinkKinds() {
    }

    public static SinkKind kindOf(String channel) {
        if (channel == null || channel.isBlank()) {
            return SinkKind.IM;
        }
        return switch (channel.toLowerCase(Locale.ROOT)) {
            case "email" -> SinkKind.MAIL;
            // feishu / wecom / dingtalk / sms→IM 口径待 NTF-02 渠道接入时按需要细分
            default -> SinkKind.IM;
        };
    }
}
