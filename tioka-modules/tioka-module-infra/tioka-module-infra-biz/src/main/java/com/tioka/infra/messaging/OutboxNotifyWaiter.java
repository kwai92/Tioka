package com.tioka.infra.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 专用 JDBC 连接 {@code LISTEN tioka_outbox}（不经连接池），唤醒中继空闲等待。
 *
 * <p>通过反射调用 {@code org.postgresql.PGConnection}，避免把 postgresql 提到 compile 作用域。
 * 与 {@link OutboxRelay} 同生命周期；relay 禁用时不启动。
 */
@Component
public class OutboxNotifyWaiter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxNotifyWaiter.class);
    private static final String CHANNEL = "tioka_outbox";
    private static final long STOP_JOIN_MS = 2_000L;

    private final Environment env;
    private final MessagingProperties props;
    private final BlockingQueue<Object> signals = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread listenThread;
    private volatile Connection listenConnection;

    public OutboxNotifyWaiter(Environment env, MessagingProperties props) {
        this.env = env;
        this.props = props;
    }

    @Override
    public void start() {
        if (!props.isRelayEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        listenThread = new Thread(this::listenLoop, "tioka-outbox-listen");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        running.set(false);
        wakeup();
        Connection c = listenConnection;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        Thread t = listenThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 101;
    }

    public void await(long idleWaitMs) throws InterruptedException {
        signals.poll(idleWaitMs, TimeUnit.MILLISECONDS);
    }

    public void wakeup() {
        signals.offer(Boolean.TRUE);
    }

    private void listenLoop() {
        while (running.get()) {
            String url = env.getProperty("spring.datasource.url");
            String user = env.getProperty("spring.datasource.username");
            String password = env.getProperty("spring.datasource.password");
            if (url == null || url.isBlank()) {
                sleepQuiet(1000);
                continue;
            }
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement st = conn.createStatement()) {
                listenConnection = conn;
                conn.setAutoCommit(true);
                st.execute("LISTEN " + CHANNEL);
                log.info("LISTEN {} 已建立专用连接", CHANNEL);
                Class<?> pgType = Class.forName("org.postgresql.PGConnection");
                Object pg = conn.unwrap(pgType);
                Method getNotifications = pgType.getMethod("getNotifications", int.class);
                while (running.get()) {
                    Object notes = getNotifications.invoke(pg, 500);
                    if (notes instanceof Object[] arr && arr.length > 0) {
                        wakeup();
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("LISTEN 连接中断，1s 后重试: {}", e.toString());
                    sleepQuiet(1000);
                }
            } finally {
                listenConnection = null;
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
