package com.tioka.runtime.kernel.infrastructure.redis;

import java.util.Optional;

/**
 * 同一 thread 同时只能有一个 Turn 持锁执行；值带 fencing token（instanceId:turnId）。
 */
public interface SessionLock {
    /** 成功返回 Handle；已被占用返回 empty。 */
    Optional<Handle> tryAcquire(String threadId, String turnId);

    interface Handle extends AutoCloseable {
        /** Lua：值相等才 PEXPIRE；失败说明锁已丢。 */
        boolean renew();

        /** Lua：值相等才 DEL。 */
        @Override
        void close();
    }
}
