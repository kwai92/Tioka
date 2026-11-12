package com.tioka.server.audit;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.audit.AuditChainService;
import com.tioka.system.infrastructure.AuditEventRepository;
import com.tioka.system.infrastructure.mapper.AuditEventMapper;
import com.tioka.system.infrastructure.persistence.AuditEventQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-20：PostgreSQL 驼峰列别名折叠缺陷回归（OBS-01 查询 / OBS-05 转发 / GOV-09 哈希链）。
 *
 * <p>缺陷机理：{@code resultType=HashMap} 以 JDBC column label 为键，PostgreSQL 会把
 * 未加引号的 {@code AS workspaceId} 折叠成 {@code workspaceid}。修复前
 * {@code AuditService.toEvent} 的所有驼峰取键恒为 null（details/workspaceId/traceId 等回读全空），
 * {@code AuditChainService.groupByChain} 把所有租户事件归入 {@code _system} 单链，
 * 且 {@code canonical} 对一串空字段算哈希（篡改这些字段检不出）。
 *
 * <p>本 IT 走真实 PostgreSQL：①查询回读字段与 details；②双租户分链 + 锚 + verify 通过；
 * ③篡改 details_json 后 verify 必须判为不完整（证明规范化串真的纳入了该字段）。
 *
 * <p><b>共享 DB 隔离口径</b>：Testcontainers PG/Redis 为 JVM 单例、failsafe runOrder=random，
 * 且上下文未关闭 scheduling（@Scheduled 封印每 60s 跑一轮）。因此封印必须：只作用于本用例的
 * 随机 tag 行（用过滤后的仓储视图，绝不封印其它 IT 的记录）、复用生产同一把 Redis 锁避免与
 * @Scheduled 并发双封、篡改后恢复原值、结束时删除本用例的事件与封印锚。
 */
@SpringBootTest
@Tag("it")
class AuditAliasIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AuditApi audit;
    @Autowired AuditEventRepository repository;
    @Autowired AuditEventMapper mapper;
    @Autowired AuditChainService chain;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired StringRedisTemplate redis;

    /** 本用例写入的 action / workspace，供 @AfterEach 精确清理（避免污染共享库）。 */
    private final List<String> actions = new ArrayList<>();
    private final List<String> workspaces = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String ws : workspaces) {
            jdbc.update("DELETE FROM audit_seal WHERE workspace_key = ?", ws);
        }
        for (String action : actions) {
            jdbc.update("DELETE FROM audit_event WHERE action = ?", action);
        }
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void writeEvent(String tag, String workspaceId, String action) {
        actions.add(action);
        workspaces.add(workspaceId);
        audit.record(AuditEvent.builder()
                .workspaceId(workspaceId)
                .actorType("user")
                .actorId("u_" + tag)
                .action(action)
                .resourceType("contract")
                .resourceId("r_" + tag)
                .outcome(AuditOutcome.SUCCESS)
                .reason("C-20 alias regression")
                .traceId("tr_" + tag)
                .spanId("sp_" + tag)
                .service("tioka-it")
                .requestId("req_" + tag)
                .clientIp("127.0.0.1")
                .details(Map.of("policyIds", List.of("pol_a", "pol_b"), "marker", tag))
                .build());
    }

    @Test
    void camelCaseFieldsSurviveQueryRoundTrip() {
        String t = tag();
        String ws = "ws_alias_" + t;
        String action = "it.audit.alias." + t;
        writeEvent(t, ws, action);

        List<AuditEvent> found = audit.query(new AuditApi.AuditQuery(
                ws, null, action, null, null, null, null, null, null, 10));
        assertEquals(1, found.size(), "按 workspace + action 应精确命中 1 条");
        AuditEvent e = found.get(0);

        assertEquals(ws, e.workspaceId(), "workspaceId 必须回读（修复前恒 null）");
        assertEquals("u_" + t, e.actorId());
        assertEquals("user", e.actorType());
        assertEquals("contract", e.resourceType());
        assertEquals("r_" + t, e.resourceId());
        assertEquals("tr_" + t, e.traceId());
        assertEquals("sp_" + t, e.spanId());
        assertEquals("req_" + t, e.requestId());
        assertEquals("127.0.0.1", e.clientIp());
        assertEquals(AuditOutcome.SUCCESS, e.outcome());
        assertEquals("C-20 alias regression", e.reason());
        assertEquals(t, e.details().get("marker"), "details 必须回读（修复前恒空 Map）");
        assertEquals(List.of("pol_a", "pol_b"), e.details().get("policyIds"));

        // 仓储原始 HashMap 同样必须带驼峰键（查询 / 转发 / 封印链路共用这套别名）
        List<Map<String, Object>> rawRows = repository.query(new AuditEventQuery(
                ws, null, action, null, null, null, null, null, 10, 0));
        assertEquals(1, rawRows.size());
        Map<String, Object> raw = rawRows.get(0);
        assertEquals("u_" + t, String.valueOf(raw.get("actorId")));
        assertNotNull(raw.get("detailsJson"), "detailsJson 原始列必须可读");
    }

    @Test
    void sealSeparatesWorkspaceChainsAndDetectsDetailsTampering() {
        String t = tag();
        String wsA = "ws_seal_a_" + t;
        String wsB = "ws_seal_b_" + t;
        String action = "it.audit.seal." + t;
        writeEvent(t, wsA, action);
        writeEvent(t, wsB, action);

        // 修复前 groupByChain 取不到 workspaceId → 两条都进 _system 链，下面两条锚都不存在
        sealOnly(action);

        Integer anchorA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_seal WHERE workspace_key = ?", Integer.class, wsA);
        Integer anchorB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_seal WHERE workspace_key = ?", Integer.class, wsB);
        assertEquals(1, anchorA, "工作空间 A 应有独立封印锚（每租户一条链）");
        assertEquals(1, anchorB, "工作空间 B 应有独立封印锚（每租户一条链）");

        var verifyA = chain.verify(wsA);
        var verifyB = chain.verify(wsB);
        assertTrue(verifyA.intact(), () -> "A 链校验：" + verifyA.summary());
        assertTrue(verifyB.intact(), () -> "B 链校验：" + verifyB.summary());
        assertEquals(1, verifyA.checked(), "A 链应恰好 1 条（不能混入 B）");
        assertEquals(1, verifyB.checked(), "B 链应恰好 1 条（不能混入 A）");

        // 篡改 details_json：修复前 canonical 该字段恒为空 → 重算哈希不变 → 检不出
        String original = jdbc.queryForObject(
                "SELECT details_json FROM audit_event WHERE action = ? AND workspace_id = ?",
                String.class, action, wsA);
        try {
            jdbc.update("UPDATE audit_event SET details_json = ? WHERE action = ? AND workspace_id = ?",
                    "{\"marker\":\"tampered\"}", action, wsA);
            var tampered = chain.verify(wsA);
            assertFalse(tampered.intact(),
                    () -> "details_json 被篡改后链必须判为不完整，实际：" + tampered.summary());
        } finally {
            // 恢复原值：共享 DB 里不留一条被破坏的链，也便于复跑
            jdbc.update("UPDATE audit_event SET details_json = ? WHERE action = ? AND workspace_id = ?",
                    original, action, wsA);
        }
        assertTrue(chain.verify(wsA).intact(), "恢复 details_json 后链应重新完整");
    }

    /**
     * 只封印本用例 tag 的两条记录，绝不触碰其它 IT 的未封印行。
     *
     * <p>做法：给 {@link AuditChainService} 注入一个「listUnsealed 只看本 tag」的仓储视图，
     * 其余方法（markSealed / getSeal / upsertSeal）仍走真实 DB。视图在持锁期间实时读取，
     * 因此 @Scheduled 若已抢先封印，视图自然返回空集，不会双封；Redis 锁与生产共用同一把键，
     * 保证两者互斥。锁被占用时重试，最终以 DB 中的 hash 为准。
     */
    private void sealOnly(String action) {
        // 用真实 Mapper 构造一个「只看本 tag」的仓储子类：其余方法仍走原生产 SQL，
        // 且不受 Spring CGLIB 代理影响（Mockito.spy 在代理 bean 上会失败）。
        AuditEventRepository scopedRepo = new AuditEventRepository(mapper) {
            @Override
            public List<Map<String, Object>> listUnsealed(int ignored) {
                Integer pending = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_event WHERE hash IS NULL", Integer.class);
                int limit = Math.max(2, (pending == null ? 0 : pending) + 20);
                return super.listUnsealed(limit).stream()
                        .filter(row -> action.equals(String.valueOf(row.get("action"))))
                        .toList();
            }
        };

        AuditChainService scopedChain = new AuditChainService(scopedRepo, txManager, redis);
        for (int attempt = 0; attempt < 15; attempt++) {
            Integer sealed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE action = ? AND hash IS NOT NULL",
                    Integer.class, action);
            if (sealed != null && sealed == 2) {
                return;
            }
            var outcome = scopedChain.sealPending(2);
            sleep(outcome instanceof AuditChainService.SealOutcome.SkippedByLock ? 250 : 60);
        }
        throw new AssertionError("按 tag 精确封印未生效，action=" + action);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
