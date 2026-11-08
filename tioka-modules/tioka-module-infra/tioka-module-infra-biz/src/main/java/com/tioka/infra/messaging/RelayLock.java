package com.tioka.infra.messaging;

/**
 * Outbox 中继单例锁（Redis {@code mq:relay:lock}）。
 */
public interface RelayLock {

    boolean tryAcquire(String token);

    boolean renew(String token);

    void release(String token);
}
