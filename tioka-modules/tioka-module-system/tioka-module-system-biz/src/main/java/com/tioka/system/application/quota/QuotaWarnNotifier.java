package com.tioka.system.application.quota;

import com.tioka.system.api.notify.NotificationPort;
import com.tioka.system.api.spi.QuotaThresholdExceededEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 配额阈值预警订阅者（GAP-L5-3 / P2）：消费 {@link QuotaThresholdExceededEvent}。
 *
 * <p>出口两个：
 * <ol>
 *     <li>WARN 日志（当前部署默认可观测通道）；</li>
 *     <li>{@link NotificationPort} 可选通知——端口以 {@link ObjectProvider} 注入，
 *     未接线（当前 {@code NoopNotificationPort} 尚未注册 / M2 IM 未接）时静默跳过，
 *     调用方与约束 F21（告警一律经通知端口）对齐。</li>
 * </ol>
 * MQ outbox topic 对接随 GOV-13 / NTF-01 接线时启用，信封契约不动。
 */
@Component
public class QuotaWarnNotifier {
    private static final Logger log = LoggerFactory.getLogger(QuotaWarnNotifier.class);
    private static final String CHANNEL = "quota";

    private final ObjectProvider<NotificationPort> notifications;

    public QuotaWarnNotifier(ObjectProvider<NotificationPort> notifications) {
        this.notifications = notifications;
    }

    /**
     * 消费预警事件：记 WARN 日志，并（若通知端口已接线）发去重通知。
     * 处理失败只记日志，不影响事件发布方。
     */
    @EventListener
    public void onQuotaThreshold(QuotaThresholdExceededEvent event) {
        log.warn("配额预警: {}/{} {} 已用 {} / 上限 {}（越过 {}%）",
                event.scopeType(), event.scopeId(), event.yearMonth(),
                event.usedValue(), event.limitValue(), event.thresholdPct());
        try {
            NotificationPort port = notifications.getIfAvailable();
            if (port == null) {
                return;
            }
            port.notify(new NotificationPort.NotificationCommand(
                    CHANNEL,
                    event.scopeType() + ":" + event.scopeId(),
                    "quota_threshold_exceeded",
                    Map.of(
                            "scopeType", event.scopeType(),
                            "scopeId", event.scopeId(),
                            "metric", event.metric(),
                            "used", event.usedValue(),
                            "limit", event.limitValue(),
                            "thresholdPct", event.thresholdPct(),
                            "yearMonth", event.yearMonth()),
                    "quota-warn:" + event.scopeType() + ":" + event.scopeId()
                            + ":" + event.yearMonth() + ":" + event.thresholdPct()));
        } catch (RuntimeException e) {
            log.warn("配额预警通知失败（忽略）: {}", e.toString());
        }
    }
}
