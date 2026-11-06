package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：KNW-01、KNW-02。用例说明见 docs/architecture/construction/M1-04、M1-03。
 *
 * <p>运行环境：GateCluster（3 实例）+ Testcontainers（PostgreSQL、Redis、Keycloak）+ LLM 桩 + MCP 桩（ENG-04b）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 KNW-01、KNW-02 实现后启用")
class G1KnowledgeAclGateIT {

    /** Turn 中 kb_search 参数含无权知识库：只检索有权知识库，结果不含受限文档，审计有 tool.invoke */
    @Test
    void restrictedKbNotRetrievedInTurn() {
        fail("待实现：Turn 中 kb_search 参数含无权知识库：只检索有权知识库，结果不含受限文档，审计有 tool.invoke");
    }

    /** 知识库 ingest 与检索的嵌入调用都有 model_usage 记录，purpose=EMBEDDING */
    @Test
    void embeddingGoesThroughGateway() {
        fail("待实现：知识库 ingest 与检索的嵌入调用都有 model_usage 记录，purpose=EMBEDDING");
    }
}
