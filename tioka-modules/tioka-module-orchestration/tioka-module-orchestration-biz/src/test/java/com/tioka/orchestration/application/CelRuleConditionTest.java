package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelRuleConditionTest {

    private final CelRuleCondition cel = new CelRuleCondition();

    @Test
    void bodyAccessRejectedAtCompile() {
        BizException ex = assertThrows(BizException.class,
                () -> cel.compileOrThrow("rv_1", "event.body != null"));
        assertTrue(ex.getMessage().contains("body") || ex.getMessage().contains("CEL"));
    }

    @Test
    void simpleAttrMatch() {
        cel.compileOrThrow("rv_ok", "attrs.channel == 'feishu'");
        assertTrue(cel.evaluate("rv_ok", "attrs.channel == 'feishu'",
                Map.of("type", "feishu.message"),
                Map.of("channel", "feishu")));
        assertFalse(cel.evaluate("rv_ok", "attrs.channel == 'feishu'",
                Map.of("type", "feishu.message"),
                Map.of("channel", "web")));
    }

    @Test
    void evalTimeoutRaisesCelEvalException() {
        ExecutorService hanging = new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                shutdown = true;
                return java.util.List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                // 不执行，让 Future.get 超时
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                FutureTask<T> ft = new FutureTask<>(() -> {
                    Thread.sleep(5_000);
                    return task.call();
                });
                // 故意不 start，get(timeout) → TimeoutException
                return ft;
            }
        };
        CelRuleCondition slow = new CelRuleCondition(hanging, 10L);
        slow.compileOrThrow("rv_slow", "true");
        CelRuleCondition.CelEvalException ex = assertThrows(CelRuleCondition.CelEvalException.class,
                () -> slow.evaluate("rv_slow", "true", Map.of(), Map.of()));
        assertTrue(ex.reason().contains("timeout") || ex.getMessage().contains("超时"));
    }
}
