package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelRuleConditionTest {

    /**
     * 求值超时在生产是 50ms 的 DoS 预算（{@code CelRuleCondition.EVAL_TIMEOUT_MS}），
     * 但本类的用例断言的是**语义**（body 禁访、attrs 匹配），不是时延。
     *
     * <p>用生产 50ms 会让首次求值的冷启动（线程池创建 + CEL 运行时类加载）在**高负载构建下**
     * 被击穿：实证为 `mvn -fae test` 全量跑时 `simpleAttrMatch` 报「CEL 求值超时 50ms」而 RC=1，
     * 单模块跑 1.0s 通过、CI backend 0.363s 通过。一个随机变红的门禁等于没有门禁，
     * 故此处显式放宽到 5s；超时语义本身由 {@link #evalTimeoutRaisesCelEvalException} 用
     * 独立挂起线程池 + 10ms 精确覆盖，放宽本处不减少任何覆盖面。
     */
    private static final long EVAL_TIMEOUT_FOR_TESTS_MS = 5_000L;

    private static final ExecutorService EVAL_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orch-cel-eval-test");
        t.setDaemon(true);
        return t;
    });

    private final CelRuleCondition cel = new CelRuleCondition(EVAL_POOL, EVAL_TIMEOUT_FOR_TESTS_MS);

    @AfterAll
    static void shutdownPool() {
        EVAL_POOL.shutdownNow();
    }

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
