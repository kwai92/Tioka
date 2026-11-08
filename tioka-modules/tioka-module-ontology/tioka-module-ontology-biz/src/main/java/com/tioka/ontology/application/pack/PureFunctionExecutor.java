package com.tioka.ontology.application.pack;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 领域包钩子专用线程池：超时隔离；调用方不得在钩子内访问 JDBC / HTTP（由类加载器与清单权限约束）。
 */
public final class PureFunctionExecutor implements AutoCloseable {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String THREAD_NAME_PREFIX = "ont-pack-pure-";

    private final ExecutorService executor;
    private final long timeoutSeconds;

    public PureFunctionExecutor() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    public PureFunctionExecutor(long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, THREAD_NAME_PREFIX + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    public <T> T run(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        try {
            return executor.submit(task).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("domain pack hook timed out after " + timeoutSeconds + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("domain pack hook failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("domain pack hook interrupted", e);
        }
    }

    public void run(Runnable task) {
        run(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
