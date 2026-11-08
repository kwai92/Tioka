package com.tioka.connector.api.sdk;

/**
 * 连接器健康状态码（M3-06 CON-SDK）。
 * 连续失败达到 {@link #DEGRADED_FAILURE_THRESHOLD} 次标为 {@link #DEGRADED}。
 */
public final class ConnectorHealthStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String DEGRADED = "DEGRADED";

    /** 连续失败次数阈值：达到后标 DEGRADED。 */
    public static final int DEGRADED_FAILURE_THRESHOLD = 3;

    private ConnectorHealthStatus() {}
}
