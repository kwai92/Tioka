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

    public boolean isFrozen() {
        return frozen.get();
    }

    /** 工具调用入口：冻结时阻塞直到中断（崩溃关闭上下文）。 */
    public void awaitUnfrozenOrInterrupt() {
        while (frozen.get()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
