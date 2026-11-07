package com.tioka.connector.api;

/** 飞书文档映射（ENG-06：server 只依赖 -api）。 */
public interface FeishuDocSyncApi {

    MappingView map(String localDocId, String feishuDocToken);

    record MappingView(String id, String localDocId, String feishuDocToken) {}
}
