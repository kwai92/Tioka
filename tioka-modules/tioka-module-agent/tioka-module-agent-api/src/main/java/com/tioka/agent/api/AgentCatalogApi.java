package com.tioka.agent.api;

import java.util.List;

/** Agent 目录查询（ENG-06：server 只依赖 -api）。 */
public interface AgentCatalogApi {

    List<AgentRef> listSummaries();

    record AgentRef(String id, String name) {}
}
