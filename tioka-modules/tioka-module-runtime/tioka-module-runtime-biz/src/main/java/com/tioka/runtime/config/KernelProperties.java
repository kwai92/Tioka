package com.tioka.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tioka.runtime")
public class KernelProperties {
    /** worker_id 与锁持有者标识，默认取 HOSTNAME。 */
    private String instanceId = System.getenv().getOrDefault("HOSTNAME", "local");
    private Duration leaseTtl = Duration.ofSeconds(30);
    private Duration leaseRenewInterval = Duration.ofSeconds(10);
    private Executor executor = new Executor();
    private int maxQueuedPerThread = 5;
    private Loop loop = new Loop();
    private Events events = new Events();
    /** LegacySessionAdapter.append 等待 Turn 终态超时。 */
    private Duration legacyAppendTimeout = Duration.ofSeconds(120);
    /**
     * 关闭上下文时是否释放 SessionLock / 让 Turn 收尾。
     * 闸门模拟崩溃时置 false，租约留给 LeaseReaper（ENG-04b）。
     */
    private boolean releaseOnShutdown = true;
    /**
     * 是否启动 {@code TurnDispatcher} 消费 {@code rt:turns}。
     * 普通 {@code @SpringBootTest} IT 与 GateCluster 共享 Redis 时关闭，避免抢消费。
     */
    private boolean dispatcherEnabled = true;
    /** 是否启用 QueuedSweeper（与 dispatcher 同理，普通 IT 关闭以免污染共享 Redis）。 */
    private boolean queuedSweeperEnabled = true;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public Duration getLeaseTtl() { return leaseTtl; }
    public void setLeaseTtl(Duration leaseTtl) { this.leaseTtl = leaseTtl; }
    public Duration getLeaseRenewInterval() { return leaseRenewInterval; }
    public void setLeaseRenewInterval(Duration leaseRenewInterval) { this.leaseRenewInterval = leaseRenewInterval; }
    public boolean isReleaseOnShutdown() { return releaseOnShutdown; }
    public void setReleaseOnShutdown(boolean releaseOnShutdown) { this.releaseOnShutdown = releaseOnShutdown; }
    public boolean isDispatcherEnabled() { return dispatcherEnabled; }
    public void setDispatcherEnabled(boolean dispatcherEnabled) { this.dispatcherEnabled = dispatcherEnabled; }
    public boolean isQueuedSweeperEnabled() { return queuedSweeperEnabled; }
    public void setQueuedSweeperEnabled(boolean queuedSweeperEnabled) {
        this.queuedSweeperEnabled = queuedSweeperEnabled;
    }
    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }
    public int getMaxQueuedPerThread() { return maxQueuedPerThread; }
    public void setMaxQueuedPerThread(int maxQueuedPerThread) { this.maxQueuedPerThread = maxQueuedPerThread; }
    public Loop getLoop() { return loop; }
    public void setLoop(Loop loop) { this.loop = loop; }
    public Events getEvents() { return events; }
    public void setEvents(Events events) { this.events = events; }
    public Duration getLegacyAppendTimeout() { return legacyAppendTimeout; }
    public void setLegacyAppendTimeout(Duration legacyAppendTimeout) {
        this.legacyAppendTimeout = legacyAppendTimeout;
    }

    public static class Executor {
        private int maxConcurrentTurns = 64;
        private int queueCapacity = 256;

        public int getMaxConcurrentTurns() { return maxConcurrentTurns; }
        public void setMaxConcurrentTurns(int maxConcurrentTurns) { this.maxConcurrentTurns = maxConcurrentTurns; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Loop {
        private int maxSteps = 20;
        private int toolParallelism = 4;
        private Duration toolTimeout = Duration.ofSeconds(60);
        private int repeatCallLimit = 3;

        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
        public int getToolParallelism() { return toolParallelism; }
        public void setToolParallelism(int toolParallelism) { this.toolParallelism = toolParallelism; }
        public Duration getToolTimeout() { return toolTimeout; }
        public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }
        public int getRepeatCallLimit() { return repeatCallLimit; }
        public void setRepeatCallLimit(int repeatCallLimit) { this.repeatCallLimit = repeatCallLimit; }
    }

    public static class Events {
        private long streamMaxlen = 10000;
        private Duration streamTtlRunning = Duration.ofHours(1);
        private Duration streamTtlAfterTurn = Duration.ofMinutes(10);
        private Duration sseHeartbeat = Duration.ofSeconds(15);
        private Duration sseTimeout = Duration.ofMinutes(30);
        private int maxConnections = 2000;

        public long getStreamMaxlen() { return streamMaxlen; }
        public void setStreamMaxlen(long streamMaxlen) { this.streamMaxlen = streamMaxlen; }
        public Duration getStreamTtlRunning() { return streamTtlRunning; }
        public void setStreamTtlRunning(Duration streamTtlRunning) { this.streamTtlRunning = streamTtlRunning; }
        public Duration getStreamTtlAfterTurn() { return streamTtlAfterTurn; }
        public void setStreamTtlAfterTurn(Duration streamTtlAfterTurn) { this.streamTtlAfterTurn = streamTtlAfterTurn; }
        public Duration getSseHeartbeat() { return sseHeartbeat; }
        public void setSseHeartbeat(Duration sseHeartbeat) { this.sseHeartbeat = sseHeartbeat; }
        public Duration getSseTimeout() { return sseTimeout; }
        public void setSseTimeout(Duration sseTimeout) { this.sseTimeout = sseTimeout; }
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    }
}
