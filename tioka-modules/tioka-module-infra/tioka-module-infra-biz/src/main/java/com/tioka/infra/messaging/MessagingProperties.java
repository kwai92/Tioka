package com.tioka.infra.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code tioka.messaging.*}（ORC-02 / M2-01）。
 */
@ConfigurationProperties(prefix = "tioka.messaging")
public class MessagingProperties {

    /** {@code rocketmq} | {@code in-memory}（仅测试）。 */
    private String mode = "in-memory";

    private final Relay relay = new Relay();
    private final Consumer consumer = new Consumer();

    /** 开发 / 测试校验信封 schema；生产关闭。 */
    private boolean validateEnvelope = true;

    /** 是否启动中继后台线程；IT/local 默认关，避免与 runtime LeaseReaper 串扰。 */
    private boolean relayEnabled = false;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Relay getRelay() {
        return relay;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public boolean isValidateEnvelope() {
        return validateEnvelope;
    }

    public void setValidateEnvelope(boolean validateEnvelope) {
        this.validateEnvelope = validateEnvelope;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public boolean inMemory() {
        return !"rocketmq".equalsIgnoreCase(mode);
    }

    public static class Relay {
        private int batch = 200;
        private Duration idleWait = Duration.ofMillis(200);
        private Duration lockTtl = Duration.ofSeconds(15);
        private Duration lockRenewInterval = Duration.ofSeconds(5);
        private Duration retention = Duration.ofDays(7);

        public int getBatch() {
            return batch;
        }

        public void setBatch(int batch) {
            this.batch = batch;
        }

        public Duration getIdleWait() {
            return idleWait;
        }

        public void setIdleWait(Duration idleWait) {
            this.idleWait = idleWait;
        }

        public Duration getLockTtl() {
            return lockTtl;
        }

        public void setLockTtl(Duration lockTtl) {
            this.lockTtl = lockTtl;
        }

        public Duration getLockRenewInterval() {
            return lockRenewInterval;
        }

        public void setLockRenewInterval(Duration lockRenewInterval) {
            this.lockRenewInterval = lockRenewInterval;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }

    public static class Consumer {
        private int maxAttempts = 3;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
