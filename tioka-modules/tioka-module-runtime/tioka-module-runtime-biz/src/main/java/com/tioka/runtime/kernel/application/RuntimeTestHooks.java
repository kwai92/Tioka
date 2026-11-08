package com.tioka.runtime.kernel.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ENG-04b：仅 gate profile 注册的测试钩子。
 * {@link #freeze()} 停续租并阻塞工具调用，配合 {@code GateCluster.crash} 模拟崩溃。
 */
@Component
@Profile("gate")
public class RuntimeTestHooks {
    private final AtomicBoolean frozen = new AtomicBoolean(false);

    /** 停续租线程侧检查点、阻塞正在执行的工具。 */
    public void freeze() {
        frozen.set(true);
    }

    /** 崩溃接管后解冻续跑实例。 */
    public void unfreeze() {
        frozen.set(false);
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    /**
     * 工具调用入口：冻结时阻塞。关闭上下文中断线程时抛 {@link CrashFrozenAbort}，
     * 由 {@link TurnRunner} 捕获后不写终态，留给 {@link LeaseReaper}。
     */
    public void awaitUnfrozenOrInterrupt() {
        while (frozen.get()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (frozen.get()) {
                    throw new CrashFrozenAbort();
                }
                return;
            }
        }
    }

    /** 崩溃模拟：冻结中被中断，Turn 不得自行 FAILED。 */
    public static final class CrashFrozenAbort extends RuntimeException {
        public CrashFrozenAbort() {
            super("runtime test hook frozen abort");
        }
    }
}
