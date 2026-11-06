package com.tioka.system.application.lifecycle;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后再发 Spring 事件（FND-04e：避免回滚后仍通知监听方）。
 */
public final class AfterCommitEvents {
    private AfterCommitEvents() {}

    public static void publish(ApplicationEventPublisher publisher, Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishEvent(event);
                }
            });
        } else {
            publisher.publishEvent(event);
        }
    }
}
