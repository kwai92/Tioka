package com.tioka.server.gate.support.mail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * ENG-04d：SMTP 收件捕获桩（内存版，避免在 PK-01 前追加 greenmail 依赖）。
 * PK-01 合入后可替换为 GreenMail / Testcontainers，并迁入 gate/support/mail。
 */
public final class SmtpCaptureStub implements AutoCloseable {

    public record CapturedMail(String to, String subject, String body) {}

    private final List<CapturedMail> mails = new CopyOnWriteArrayList<>();
    private final int port;

    private SmtpCaptureStub(int port) {
        this.port = port;
    }

    public static SmtpCaptureStub start() {
        return new SmtpCaptureStub(2525);
    }

    public int smtpPort() {
        return port;
    }

    public String host() {
        return "127.0.0.1";
    }

    /** 测试或业务代码投递时调用。 */
    public void accept(String to, String subject, String body) {
        mails.add(new CapturedMail(to, subject, body));
    }

    public List<CapturedMail> messages() {
        return List.copyOf(mails);
    }

    public CapturedMail waitForMessage(String toContains, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (CapturedMail m : mails) {
                if (m.to() != null && m.to().contains(toContains)) {
                    return m;
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("no mail to " + toContains + " within " + timeout);
    }

    @Override
    public void close() {
        mails.clear();
    }
}
