package com.tioka.connector.api.sdk;

/**
 * 一次同步动作。成功时 SDK 才写入 {@code nextCursorOnSuccess}；失败不前移游标。
 */
@FunctionalInterface
public interface ConnectorSyncAction<T> {

    T execute(ConnectorSyncContext ctx) throws Exception;
}
