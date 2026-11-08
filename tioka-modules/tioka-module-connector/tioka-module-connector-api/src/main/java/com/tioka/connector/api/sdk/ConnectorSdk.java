package com.tioka.connector.api.sdk;

/**
 * 通用连接器 SDK 入口（M3-06 CON-SDK）。具体能力由连接器实现注册。
 */
public interface ConnectorSdk {

    ConnectorSpec spec(String connectorType);

    ConnectorHealth health(String connectorInstanceId);

    /** 读取同步游标；失败场景不得前移（契约由实现保证）。 */
    String readCursor(String connectorInstanceId);

    void writeCursor(String connectorInstanceId, String cursor);
}
