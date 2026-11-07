package com.tioka.infra.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
 */
@Component
public class OutboxNotifyWaiter {

    private static final Logger log = LoggerFactory.getLogger(OutboxNotifyWaiter.class);
    private static final String CHANNEL = "tioka_outbox";

    private final Environment env;
    private final BlockingQueue<Object> signals = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listenThread;

    public OutboxNotifyWaiter(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        listenThread = new Thread(this::listenLoop, "tioka-outbox-listen");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    @PreDestroy
    void stop() {
        running.set(false);
        wakeup();
        if (listenThread != null) {
            listenThread.interrupt();
        }
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
