package com.tioka.connector.api.sdk;

/**
 * 通用连接器 SDK 入口（M3-06 CON-SDK）。
 *
 * <p>覆盖规格、凭证引用（CredentialCipher）、游标（失败不前移）、实例令牌桶限流、
 * 可重试退避、健康（连续失败 3 次 DEGRADED）与出网（EgressGuard）。
 */
public interface ConnectorSdk {

    ConnectorSpec spec(String connectorType);

    ConnectorHealth health(String connectorInstanceId);

    /** 读取同步游标。 */
    String readCursor(String connectorInstanceId);

    /**
     * 直接写入游标（运维/修复用）。正常同步请走 {@link #runSync}，以保证失败不前移。
     */
    void writeCursor(String connectorInstanceId, String cursor);

    /** 登记实例，返回实例 ID。 */
    String registerInstance(ConnectorInstanceRegistration registration);

    /**
     * 绑定凭证：明文经 CredentialCipher 加密后只存密文引用；配置中不含明文。
     *
     * @param credentialId 凭证逻辑 ID（写入 AAD）
     */
    void bindCredential(String connectorInstanceId, String credentialId, String plaintext);

    /** 解析凭证明文；未绑定则 empty。 */
    java.util.Optional<String> resolveCredential(String connectorInstanceId);

    /**
     * 按实例令牌桶尝试取令牌；不足则返回 false（不阻塞）。
     */
    boolean tryAcquire(String connectorInstanceId);

    /**
     * 执行同步：限流 → 可重试执行 → 成功才写游标并记成功；失败不前移游标并记失败。
     *
     * @param nextCursorOnSuccess 成功后写入的游标；null 表示保持原游标
     */
    <T> T runSync(String connectorInstanceId,
                  String nextCursorOnSuccess,
                  ConnectorSyncAction<T> action);

    /** 出网校验（委托 EgressGuard）。 */
    void assertEgressAllowed(String url);
}
