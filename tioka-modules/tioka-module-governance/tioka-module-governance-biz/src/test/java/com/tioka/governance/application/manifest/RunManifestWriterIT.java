package com.tioka.governance.application.manifest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.persistence.UtcLocalDateTimeTypeHandler;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.manifest.RunManifestWriteRequest;
import com.tioka.governance.api.manifest.RunManifestWriter;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.infrastructure.manifest.RunManifestRepository;
import com.tioka.governance.infrastructure.mapper.RunManifestMapper;
import com.tioka.governance.infrastructure.persistence.RunManifestDO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-06 运行清单 PG 集成测试（V228 run_manifest 直建 DDL，做法同 {@code PolicyModeIT}）：
 * 首次写入 seq=1；内容不变续写幂等复用；内容变化 seq 递增追加；事件登记为
 * {@code tioka.obs.run_manifest_written.v1}。
 */
class RunManifestWriterIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_manifest")
            .withUsername("tioka")
            .withPassword("tioka");

    static SqlSessionFactory factory;
    static RunManifestRepository repository;
    static RunManifestWriter writer;
    static final List<DomainEvent> EVENTS = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        migrate(dataSource());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource());
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/RunManifest*.xml"));
        bean.setConfiguration(new MybatisConfiguration());
        factory = bean.getObject();
        factory.getConfiguration().getTypeHandlerRegistry()
                .register(LocalDateTime.class, UtcLocalDateTimeTypeHandler.class);
        // MP 注解 Mapper 需显式注册（无 MapperScan）
        factory.getConfiguration().addMapper(RunManifestMapper.class);
        repository = new RunManifestRepository(
                new SqlSessionTemplate(factory).getMapper(RunManifestMapper.class));
        writer = new DefaultRunManifestWriter(repository, EVENTS::add, noPrincipal(), new ObjectMapper());
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

    /** DDL 与 V228__m3_obs06_run_manifest.sql 同口径（列 / 约束 / 索引）。 */
    static void migrate(DataSource ds) {
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS run_manifest (
                        id                VARCHAR(32)  PRIMARY KEY,
                        run_kind          VARCHAR(16)  NOT NULL CHECK (run_kind IN ('TURN', 'ORCH_RUN', 'FLOW_RUN')),
                        run_id            VARCHAR(64)  NOT NULL,
                        seq               INT          NOT NULL DEFAULT 1,
                        request_id        VARCHAR(64),
                        parent_request_id VARCHAR(64),
                        workspace_id      VARCHAR(64),
                        subject           VARCHAR(255),
                        trace_id          VARCHAR(64),
                        agent_version_id  VARCHAR(64),
                        spec_hash         VARCHAR(64),
                        grant_id          VARCHAR(64),
                        manifest_json     JSONB        NOT NULL DEFAULT '{}',
                        content_hash      VARCHAR(64)  NOT NULL,
                        prev_hash         VARCHAR(64),
                        hash              VARCHAR(64),
                        create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        create_by         VARCHAR(64)  NOT NULL DEFAULT 'system'
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_run_manifest_run ON run_manifest (run_kind, run_id, seq)");
        } catch (Exception e) {
            throw new IllegalStateException("migrate failed", e);
        }
    }

    private static PrincipalHolder noPrincipal() {
        return new PrincipalHolder() {
            @Override
            public Principal current() {
                throw new IllegalStateException("no principal");
            }

            @Override
            public Optional<Principal> find() {
                return Optional.empty();
            }

            @Override
            public <T> T runAs(Principal principal, Supplier<T> body) {
                return body.get();
            }
        };
    }

    private static RunManifestWriteRequest req(String runId, String grantId, Map<String, String> versions) {
        return new RunManifestWriteRequest("TURN", runId, null, "ws1", "av1", grantId, "spec-hash-1", versions);
    }

    @Test
    void firstWriteThenIdempotentThenAppendOnChange() {
        String runId = "run_obs06_a";
        String first = writer.write(req(runId, "grant-1", Map.of("agentSpec", "v1", "policySet", "v3")));
        assertNotNull(first);
        List<RunManifestDO> rows = repository.listByRun("TURN", runId);
        assertEquals(1, rows.size(), "首次写入仅一条");
        assertEquals(1, rows.get(0).getSeq());
        assertEquals("ws1", rows.get(0).getWorkspaceId());
        assertEquals("av1", rows.get(0).getAgentVersionId());
        assertEquals(64, rows.get(0).getContentHash().length(), "contentHash 为 SHA-256 hex");
        assertTrue(rows.get(0).getManifestJson().contains("agentSpec"), "manifest_json 落版本集合");

        // 续跑且内容不变：幂等复用，不追加
        String again = writer.write(req(runId, "grant-1", Map.of("agentSpec", "v1", "policySet", "v3")));
        assertEquals(first, again, "内容不变续写复用同一清单");
        assertEquals(1, repository.listByRun("TURN", runId).size(), "内容不变不追加行");

        // 内容有变化：seq 递增追加
        String changed = writer.write(req(runId, "grant-1", Map.of("agentSpec", "v2", "policySet", "v3")));
        assertTrue(!first.equals(changed));
        List<RunManifestDO> after = repository.listByRun("TURN", runId);
        assertEquals(2, after.size(), "内容变化追加一条");
        assertEquals(2, after.get(1).getSeq(), "seq 递增");

        // 同类近期清单（差异对比入口）
        List<RunManifestDO> recent = repository.listRecentSameKind("ws1", "TURN", "av1", 10);
        assertEquals(2, recent.size());
    }

    @Test
    void publishesRunManifestWrittenEvent() {
        int before = EVENTS.size();
        writer.write(req("run_obs06_b", "grant-2", Map.of("agentSpec", "v1")));
        assertTrue(EVENTS.size() > before, "写入须登记事件");
        DomainEvent event = EVENTS.get(EVENTS.size() - 1);
        assertEquals(GovernanceMqTopics.RUN_MANIFEST_WRITTEN, event.event());
        assertEquals(GovernanceMqTopics.TOPIC, event.topic());
        assertEquals("TURN:run_obs06_b", event.payload().get("runRef"));
        assertEquals("TURN", event.payload().get("runKind"));
    }
}
