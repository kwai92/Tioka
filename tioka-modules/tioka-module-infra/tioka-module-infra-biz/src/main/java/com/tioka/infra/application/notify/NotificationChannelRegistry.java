package com.tioka.infra.application.notify;

import com.tioka.infra.api.notify.NotificationChannel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知渠道注册表（F35 调用方只认渠道代码）：按 {@code channel()} 小写建 map，
 * 重复渠道先到先得。渠道实现以 Spring Bean 注入（AutoConfiguration 装配）。
 */
public class NotificationChannelRegistry {

    private final Map<String, NotificationChannel> channels;

    public NotificationChannelRegistry(List<NotificationChannel> channelList) {
        this.channels = channelList == null ? Map.of() : channelList.stream()
                .collect(Collectors.toMap(
                        c -> c.channel().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a));
    }

    public Optional<NotificationChannel> find(String channel) {
        if (channel == null || channel.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(channels.get(channel.toLowerCase(Locale.ROOT)));
    }
}
