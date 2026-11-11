package com.tioka.infra.application.notify;

import com.tioka.infra.api.notify.ChannelCapabilities;
import com.tioka.infra.api.notify.DeliveryRef;
import com.tioka.infra.api.notify.Notice;
import com.tioka.infra.api.notify.NotificationChannel;
import com.tioka.infra.api.notify.Recipient;

/**
 * 空测试渠道（NTF-01）：仅测试 / 联调用，使 dedup / retry 验收可端到端闭环；
 * 正式 feishu / email 渠道留 NTF-02（范围冻结，本批不实现）。
 * 注册受 {@code tioka.notify.noop-channel.enabled} 开关（缺省 false）。
 */
public class NoopTestChannel implements NotificationChannel {

    public static final String CHANNEL = "noop";

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return new ChannelCapabilities(false, false, 4096, false);
    }

    @Override
    public DeliveryRef send(Recipient to, Notice notice) {
        String key = notice == null || notice.noticeKey() == null ? "" : notice.noticeKey();
        String ref = "noop-" + key;
        return new DeliveryRef(CHANNEL, ref.length() > 120 ? ref.substring(0, 120) : ref);
    }
}
