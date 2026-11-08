package com.tioka.connector.sdk;

import java.util.Optional;

/** 连接器实例存储端口（内存实现供契约测试；DB 实现对接 V216）。 */
interface ConnectorInstanceStore {

    void save(ConnectorInstanceState state);

    Optional<ConnectorInstanceState> find(String instanceId);
}
