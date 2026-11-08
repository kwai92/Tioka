package com.tioka.connector.api.sdk;

/**
 * 连接器类型 SPI：声明规格；实际同步逻辑由调用方经 {@link ConnectorSdk#runSync} 执行。
 */
public interface ConnectorTypeHandler {

    String type();

    ConnectorSpec spec();
}
