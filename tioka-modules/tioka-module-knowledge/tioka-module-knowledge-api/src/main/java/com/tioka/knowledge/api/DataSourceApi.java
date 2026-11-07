package com.tioka.knowledge.api;

/** 知识库数据源绑定（ENG-06：server 只依赖 -api）。 */
public interface DataSourceApi {

    DataSourceView bind(String kbId, String type, String uri);

    record DataSourceView(String id, String kbId, String type, String uri, String status) {}
}
