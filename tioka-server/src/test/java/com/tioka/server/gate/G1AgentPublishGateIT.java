package com.tioka.server.gate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 闸门 G1：AST-01、AST-02。用例说明见 docs/architecture/construction/M1-05。
 *
 * <p>运行环境：GateCluster（3 实例）+ Testcontainers（PostgreSQL、Redis、Keycloak）+ LLM 桩 + MCP 桩（ENG-04b）。
 * 实现者删除 {@code @Disabled} 并补全断言；闸门通过前不得合并对应阶段的发布分支。
 */
@Tag("gate")
@Disabled("骨架：待 AST-01、AST-02 实现后启用")
class G1AgentPublishGateIT {

    /** 发布后修改草稿与 MCP Server 工具白名单，已发布版本的 spec 与哈希不变，进行中的会话仍用原版本 */
    @Test
    void publishedSnapshotIsFrozen() {
        fail("待实现：发布后修改草稿与 MCP Server 工具白名单，已发布版本的 spec 与哈希不变，进行中的会话仍用原版本");
    }

    /** builder 引用无 USE 权限的知识库发布，返回 422 且 errors 含 REF_FORBIDDEN */
    @Test
    void publisherWithoutUseIsRejected() {
        fail("待实现：builder 引用无 USE 权限的知识库发布，返回 422 且 errors 含 REF_FORBIDDEN");
    }
}
