package com.tioka.server.auth;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.model.infrastructure.mapper.ModelUsageMapper;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B1：ScopeLine 白名单扩表后，跨空间不可见 model_usage；带 workspace 的 SYSTEM 不旁路。
 */
@SpringBootTest
@Tag("it")
class CrossWorkspaceScopeLineIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired PrincipalHolder principals;
    @Autowired ModelUsageMapper usages;
    @Autowired JdbcTemplate jdbc;

    @Test
    void modelUsageInvisibleAcrossWorkspaces() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String usageId = Ids.newId("usage");

        // 用 JDBC 写入避开 TIMESTAMPTZ→LocalDateTime 映射；读路径走 MP ScopeLine
        jdbc.update("""
                INSERT INTO model_usage(id, workspace_id, user_id, purpose, binding_id, model_ref,
                    prompt_tokens, completion_tokens, cost_micros, latency_ms, status, create_at, create_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, now(), ?)
                """, usageId, wsA, "10", "CHAT", "b_x", "m_x", 1, 1, 0L, 1, "SUCCESS", "10");

        principals.runAs(user("10", wsA), () ->
                assertEquals(1L, usages.selectCount(Wrappers.<ModelUsageDO>lambdaQuery()
                        .eq(ModelUsageDO::getId, usageId))));

        principals.runAs(user("20", wsB), () ->
                assertEquals(0L, usages.selectCount(Wrappers.<ModelUsageDO>lambdaQuery()
                        .eq(ModelUsageDO::getId, usageId))));
    }

    @Test
    void systemWithWorkspaceCannotSeeForeignUsage() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String usageId = Ids.newId("usage");

        jdbc.update("""
                INSERT INTO model_usage(id, workspace_id, user_id, purpose, binding_id, model_ref,
                    prompt_tokens, completion_tokens, cost_micros, latency_ms, status, create_at, create_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, now(), ?)
                """, usageId, wsA, "10", "CHAT", "b_x", "m_x", 1, 1, 0L, 1, "SUCCESS", "10");

        principals.runAs(system(wsB), () ->
                assertEquals(0L, usages.selectCount(Wrappers.<ModelUsageDO>lambdaQuery()
                        .eq(ModelUsageDO::getId, usageId))));

        principals.runAs(system(null), () ->
                assertEquals(1L, usages.selectCount(Wrappers.<ModelUsageDO>lambdaQuery()
                        .eq(ModelUsageDO::getId, usageId))));
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }

    private static Principal system(String ws) {
        return new Principal("0", ws, PrincipalType.SYSTEM, Set.of(), Set.of("*"), "system");
    }
}
