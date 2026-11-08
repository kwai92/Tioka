package com.tioka.connector.api.sdk;

import java.util.Optional;

/**
 * 同步回调上下文：只暴露凭证引用解析与出网校验，不含明文落库能力。
 */
public interface ConnectorSyncContext {

    String instanceId();

    String workspaceId();

    String currentCursor();

    Optional<String> resolveCredential();

    /** 出网前经 EgressGuard 校验。 */
    void assertEgressAllowed(String url);
}
