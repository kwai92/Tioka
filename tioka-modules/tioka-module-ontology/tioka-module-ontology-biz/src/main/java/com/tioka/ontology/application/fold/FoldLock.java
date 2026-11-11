package com.tioka.ontology.application.fold;

import java.util.Optional;

/**
 * 折叠互斥锁（ONT-07，M3-03 §12）：同一门店折叠串行（{@code ont:fold:{storeKey}}），
 * 不同门店并行。生产实现为 Redis（{@code RedisFoldLock}，照 runtime RedisThreadLock 模板：
 * setIfAbsent tryAcquire + compare-and-DEL release），测试可用内存实现。
 */
public interface FoldLock {

    /** 尝试获取锁；拿不到（他人持有）→ empty（调用方抛错让消息重试）。 */
    Optional<Handle> tryAcquire(String storeKey);

    /** 锁句柄；try-finally 释放，不续约（60s 租约覆盖钩子超时，过期自动失效）。 */
    interface Handle extends AutoCloseable {

        @Override
        void close();
    }
}
