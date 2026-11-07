package com.tioka.knowledge.api;

/** Wiki 页面运维（ENG-06：server 只依赖 -api）。 */
public interface WikiApi {

    WikiPageView upsert(String kbId, String title, String body);

    record WikiPageView(String id, String kbId, String title, String body) {}
}
