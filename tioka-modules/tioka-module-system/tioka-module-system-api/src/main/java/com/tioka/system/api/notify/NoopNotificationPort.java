package com.tioka.system.api.notify;

/** 空实现：未接线前丢弃通知，保证调用方可编译联调。 */
public final class NoopNotificationPort implements NotificationPort {

    @Override
    public void notify(NotificationCommand command) {
        // no-op
    }
}
