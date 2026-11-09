package com.tioka.governance.application.policy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tioka.framework.persistence.UtcLocalDateTimeTypeHandler;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyEvalRequest;
import com.tioka.governance.api.policy.PolicyEvalResult;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.application.policy.PolicyEvaluator.ResolvedPolicy;
import com.tioka.governance.infrastructure.mapper.GovPolicyMapper;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import com.tioka.governance.infrastructure.policy.GovPolicyRepository;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-07 策略存储 PG 集成测试（V200 gov_policy 直建 DDL；双实例 / Redis 广播归 CI）：
 * 导入 → 切模式 → 停用全链路、charter 整组替换版本演进（旧行保留停用）、
 * 系统级 + 空间策略合并参与 Cedar 求值（DENY 组先于 ESCALATE 组）。
 */
class PolicyModeIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_policy")
            .withUsername("tioka")
            .withPassword("tioka");

    static SqlSessionFactory factory;
    static PolicyStore store;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        migrate(dataSource());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource());
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/GovPolicy*.xml"));
        bean.setConfiguration(new MybatisConfiguration());
        factory = bean.getObject();
        factory.getConfiguration().getTypeHandlerRegistry()
                .register(LocalDateTime.class, UtcLocalDateTimeTypeHandler.class);
        // MP 注解 Mapper 需显式注册（无 MapperScan）
        factory.getConfiguration().addMapper(GovPolicyMapper.class);
        // SqlSessionTemplate 每操作独立 session，insert 即时持久化
        GovPolicyRepository repository = new GovPolicyRepository(
                new SqlSessionTemplate(factory).getMapper(GovPolicyMapper.class));
        store = new PolicyStore(repository, PolicyMode.DRYRUN.name());
    }

    @AfterAll
    static void tearDown() {
        PG.stop();
    }

    static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    static void migrate(DataSource ds) {
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS gov_policy (
                        id           VARCHAR(32)  PRIMARY KEY,
                        workspace_id VARCHAR(64),
                        version      INT          NOT NULL,
                        source       VARCHAR(64)  NOT NULL,
                        policy_id    VARCHAR(160) NOT NULL,
                        cedar_text   TEXT         NOT NULL,
                        mode         VARCHAR(8)   NOT NULL,
                        effect       VARCHAR(8)   NOT NULL,
                        status       VARCHAR(16)  NOT NULL,
                        created_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
                        create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        update_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_gov_policy_ws_source_pid ON gov_policy (workspace_id, source, policy_id, version)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_gov_policy_ws_status ON gov_policy (workspace_id, status)");
        } catch (Exception e) {
            throw new IllegalStateException("migrate failed", e);
        }
    }

    private static final String AMOUNT_CAP = """
            @id("charter:av1:pay:escalate-amount")
            @effect("ESCALATE")
            forbid(principal, action in Tioka::Action::"pay", resource)
            when { (context.agentVersion == "av1" || context.chain.contains("av1")) && resource.amountMicros > 10000 };
            """;

    @Test
    void importSetModeDisableLifecycle() {
        GovPolicyDO imported = store.importPolicy(new PolicyAdminApi.ImportCommand(
                "ws_mode", AMOUNT_CAP, null, "u1"));
        assertEquals(PolicyMode.DRYRUN.name(), imported.getMode(), "导入默认 DRYRUN");
        assertEquals(PolicyDecision.ESCALATE.name(), imported.getEffect());

        store.setMode(imported.getId(), PolicyMode.ENFORCE);
        assertEquals(PolicyMode.ENFORCE, store.detail(imported.getId()).mode());

        assertTrue(store.loadActiveForEvaluation("ws_mode").stream()
                .anyMatch(r -> imported.getId().equals(r.getId())), "ACTIVE 行参与求值");

        GovPolicyDO disabled = store.disable(imported.getId());
        assertEquals(PolicyStore.STATUS_DISABLED, disabled.getStatus());
        assertTrue(store.loadActiveForEvaluation("ws_mode").stream()
                .noneMatch(r -> imported.getId().equals(r.getId())), "停用后不参与求值");
    }

    @Test
    void charterReplaceKeepsOldVersionsDisabled() {
        int v1 = store.replaceCharterPolicies("ws_charter", "av1",
                List.of(new PolicyStore.CedarPolicySpec("charter:av1:pay:escalate-amount",
                        AMOUNT_CAP, PolicyDecision.ESCALATE)), "u1");
        assertEquals(1, v1);
        int v2 = store.replaceCharterPolicies("ws_charter", "av1",
                List.of(new PolicyStore.CedarPolicySpec("charter:av1:pay:escalate-amount",
                        AMOUNT_CAP, PolicyDecision.ESCALATE)), "u1");
        assertEquals(2, v2, "重复编译（幂等覆盖）版本递增");

        // list（listBySource 无 status 过滤）：旧行保留满足审计回溯
        List<PolicyAdminApi.PolicyView> all = store.list("ws_charter", "charter:av1");
        assertEquals(2, all.size());
        assertEquals(1, all.stream().filter(v -> PolicyStore.STATUS_DISABLED.equals(v.status())).count());
        assertEquals(1, all.stream().filter(v -> PolicyStore.STATUS_ACTIVE.equals(v.status())).count());
        assertEquals(2, all.stream().filter(v -> PolicyStore.STATUS_ACTIVE.equals(v.status()))
                .findFirst().orElseThrow().version());
    }

    @Test
    void systemLevelAndWorkspacePoliciesMergeIntoEvaluation() throws Exception {
        // 系统级（workspace_id NULL）全局 forbid → DENY
        store.importPolicy(new PolicyAdminApi.ImportCommand(
                null, "@id(\"sys-deny\")\n@effect(\"DENY\")\nforbid(principal, action, resource);",
                PolicyMode.ENFORCE, "admin"));
        // 空间级授权书上限 → ESCALATE
        store.importPolicy(new PolicyAdminApi.ImportCommand(
                "ws_merge", AMOUNT_CAP, PolicyMode.ENFORCE, "u1"));

        List<GovPolicyDO> active = store.loadActiveForEvaluation("ws_merge");
        assertTrue(active.stream().anyMatch(r -> "sys-deny".equals(r.getPolicyId())),
                "系统级（workspace_id NULL）并入求值集");
        assertTrue(active.stream().anyMatch(r -> "charter:av1:pay:escalate-amount".equals(r.getPolicyId())));

        List<ResolvedPolicy> policies = toResolved(active);
        PolicyEvaluator evaluator = new PolicyEvaluator();
        PolicyEvalResult r = evaluator.evaluate(mergeReq("ws_merge", 20000L), policies,
                store.currentSetVersion("ws_merge"), 5000);
        assertEquals(PolicyDecision.DENY, r.decision(), "DENY 组先于 ESCALATE 组");
    }

    @Test
    void workspaceCharterAloneEscalates() throws Exception {
        store.replaceCharterPolicies("ws_only_charter", "av1",
                List.of(new PolicyStore.CedarPolicySpec("charter:av1:pay:escalate-amount",
                        AMOUNT_CAP, PolicyDecision.ESCALATE)), "u1");
        List<ResolvedPolicy> policies = toResolved(store.loadActiveForEvaluation("ws_only_charter"));
        PolicyEvaluator evaluator = new PolicyEvaluator();
        PolicyEvalResult r = evaluator.evaluate(mergeReq("ws_only_charter", 20000L), policies,
                store.currentSetVersion("ws_only_charter"), 5000);
        assertEquals(PolicyDecision.ESCALATE, r.decision());
        assertEquals("charter:av1:pay:escalate-amount", r.hits().get(0).policyId());
    }

    private static List<ResolvedPolicy> toResolved(List<GovPolicyDO> rows) {
        return rows.stream()
                .map(r -> new ResolvedPolicy(r.getPolicyId(), r.getCedarText(), true,
                        PolicyDecision.valueOf(r.getEffect()), PolicyMode.valueOf(r.getMode())))
                .toList();
    }

    private static PolicyEvalRequest mergeReq(String ws, long amountMicros) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("workspaceId", ws);
        ctx.put("agentVersion", "av1");
        ctx.put("chain", List.of("av1"));
        ctx.put("actionClass", "pay");
        return new PolicyEvalRequest("user:1", "mcp.pay.run", "tool", "mcp.pay.run",
                Map.of("amountMicros", amountMicros), ctx);
    }
}
