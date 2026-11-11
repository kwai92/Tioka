package com.tioka.ontology.application.query;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import com.tioka.ontology.infrastructure.mapper.OntDraftMapper;
import com.tioka.ontology.infrastructure.mapper.OntInstanceVersionMapper;
import com.tioka.ontology.infrastructure.mapper.OntStateMapper;
import com.tioka.ontology.infrastructure.mapper.OntValueIndexMapper;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本体查询工具 PG 集成测试（ONT-03，M3-03 §8；V172 + V173 直建 DDL，独立容器隔离）：
 * {@code aclFiltered}——acl_principals 含经办人的实例可见，不含的 NOT_FOUND（不泄露存在性）；
 * 读草稿需 {@code ontology.read_draft}（无权限同口径 NOT_FOUND）。
 */
class ModelQueryIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_ont_query")
            .withUsername("tioka")
            .withPassword("tioka");

    static OntInstanceVersionRepository versions;
    static OntDraftRepository drafts;
    static OntValueIndexRepository valueIndex;
    static ModelQueryService queries;
    static EntityReadToolProvider entityRead;
    static ObjectMapper mapper;

    private static final String INSTANCE_VISIBLE = "lease:S001:HT-001";
    private static final String INSTANCE_HIDDEN = "lease:S002:HT-002";

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        mapper = new ObjectMapper();
        DataSource ds = dataSource();
        migrate(ds);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/Ont*Mapper.xml"));
        bean.setConfiguration(new MybatisConfiguration());
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().addMapper(OntDraftMapper.class);
        factory.getConfiguration().addMapper(OntInstanceVersionMapper.class);
        factory.getConfiguration().addMapper(OntValueIndexMapper.class);
        factory.getConfiguration().addMapper(OntStateMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        versions = new OntInstanceVersionRepository(template.getMapper(OntInstanceVersionMapper.class));
        drafts = new OntDraftRepository(template.getMapper(OntDraftMapper.class));
        valueIndex = new OntValueIndexRepository(template.getMapper(OntValueIndexMapper.class));
        queries = new ModelQueryService(versions, drafts);
        EntityReadToolProvider.PrincipalAccessor accessor = ctx -> ctx == null ? null : ctx.principal();
        entityRead = new EntityReadToolProvider(queries, mapper, accessor);

        seed(INSTANCE_VISIBLE, "S001", 1, List.of("agent-1"));
        seed(INSTANCE_HIDDEN, "S002", 1, List.of("store:S002"));
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
                    CREATE TABLE ont_instance_version (
                        instance_key   VARCHAR(160) NOT NULL,
                        version        INT          NOT NULL,
                        status         VARCHAR(16)  NOT NULL,
                        def_key        VARCHAR(64)  NOT NULL,
                        def_version    INT          NOT NULL,
                        store_key      VARCHAR(96)  NOT NULL,
                        model_json     JSONB        NOT NULL,
                        content_hash   CHAR(64)     NOT NULL,
                        diff_prev      JSONB,
                        diff_draft     JSONB,
                        refs_json      JSONB,
                        confirmed_by   VARCHAR(64)  NOT NULL,
                        confirmed_at   TIMESTAMPTZ  NOT NULL,
                        acl_principals VARCHAR(96)[] NOT NULL,
                        data_label     VARCHAR(16)  NOT NULL DEFAULT 'CONFIDENTIAL',
                        PRIMARY KEY (instance_key, version)
                    )
                    """);
            st.execute("""
                    CREATE TABLE ont_draft (
                        instance_key    VARCHAR(160) PRIMARY KEY,
                        def_key         VARCHAR(64)  NOT NULL,
                        def_version     INT          NOT NULL,
                        store_key       VARCHAR(96)  NOT NULL,
                        doc_ids         VARCHAR(26)[] NOT NULL,
                        model_json      JSONB        NOT NULL,
                        validation_json JSONB        NOT NULL,
                        build_run_id    VARCHAR(26)  NOT NULL,
                        biz_draft_ref   VARCHAR(128),
                        revision        INT          NOT NULL DEFAULT 1,
                        updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("""
                    CREATE TABLE ont_value_index (
                        instance_key VARCHAR(160) NOT NULL,
                        version      INT          NOT NULL,
                        path         VARCHAR(256) NOT NULL,
                        status       VARCHAR(12)  NOT NULL,
                        value_text   TEXT,
                        topics       VARCHAR(32)[],
                        PRIMARY KEY (instance_key, version, path)
                    )
                    """);
            st.execute("""
                    CREATE TABLE ont_state (
                        store_key      VARCHAR(96) PRIMARY KEY,
                        pack_key       VARCHAR(64) NOT NULL,
                        input_hash     CHAR(64)    NOT NULL,
                        state_json     JSONB       NOT NULL,
                        review_items   JSONB       NOT NULL,
                        folded_at      TIMESTAMPTZ NOT NULL,
                        written_back   BOOLEAN     NOT NULL DEFAULT false
                    )
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("migrate failed", e);
        }
    }

    private static void seed(String instanceKey, String store, int version, List<String> acl) {
        OntInstanceVersionDO row = new OntInstanceVersionDO();
        row.setInstanceKey(instanceKey);
        row.setVersion(version);
        row.setStatus("CONFIRMED");
        row.setDefKey("lease.contract");
        row.setDefVersion(1);
        row.setStoreKey("lease:" + store);
        row.setModelJson("{\"feeItems\":[]}");
        row.setContentHash("0".repeat(64));
        row.setConfirmedBy("biz-user-1");
        row.setConfirmedAt(Instant.now());
        row.setAclPrincipals(acl);
        row.setDataLabel("CONFIDENTIAL");
        versions.insert(row);
    }

    /** 经办人代理：服务账号 u_9 on_behalf_of=agent-1，无门店权限（不含 store:S002）。 */
    private static Principal agentPrincipal(String... permissions) {
        return new Principal("u_9", "ws_1", PrincipalType.USER,
                Set.of(), Set.of(permissions), "oidc", "agent-1");
    }

    private static ToolGateway.ToolCallContext ctx(Principal principal) {
        return new ToolGateway.ToolCallContext(principal, "t1", "turn1", "av1",
                "test", List.of(), Map.of(), null);
    }

    @Test
    void aclFiltered() {
        ToolProvider.ToolResult visible = entityRead.invoke(ctx(agentPrincipal()),
                "av1", "entity.read", Map.of("instanceKey", INSTANCE_VISIBLE), Map.of());
        assertTrue(visible.ok(), () -> "含经办人 acl 的实例应可见: " + visible.content());
        assertTrue(visible.content().contains(INSTANCE_VISIBLE));
        assertEquals("CONFIDENTIAL", visible.dataLabel());

        // 无门店权限的经办人读不含其 acl 的实例 → NOT_FOUND（不泄露存在性）
        ToolProvider.ToolResult hidden = entityRead.invoke(ctx(agentPrincipal()),
                "av1", "entity.read", Map.of("instanceKey", INSTANCE_HIDDEN), Map.of());
        assertFalse(hidden.ok());
        assertEquals("NOT_FOUND", hidden.content());

        // 不存在的实例同样 NOT_FOUND（口径一致）
        ToolProvider.ToolResult missing = entityRead.invoke(ctx(agentPrincipal()),
                "av1", "entity.read", Map.of("instanceKey", "lease:S001:HT-404"), Map.of());
        assertFalse(missing.ok());
        assertEquals("NOT_FOUND", missing.content());
    }

    @Test
    void draftRequiresReadDraftPermission() throws Exception {
        // 草稿行 SQL 直插（BaseMapper.insert 对 JSONB 列缺 ::jsonb 转型，生产走 XML upsert；此处只测读路径）
        try (var conn = dataSource().getConnection(); var st = conn.createStatement()) {
            st.execute("INSERT INTO ont_draft (instance_key, def_key, def_version, store_key, doc_ids, "
                    + "model_json, validation_json, build_run_id, revision, updated_at) VALUES ('"
                    + INSTANCE_VISIBLE + "', 'lease.contract', 1, 'lease:S001', '{}', "
                    + "'{\"feeItems\":[]}', '[]', 'run_1', 1, now())");
        }

        // 无 ontology.read_draft → NOT_FOUND（同口径，不泄露草稿存在性）
        ToolProvider.ToolResult denied = entityRead.invoke(ctx(agentPrincipal()),
                "av1", "entity.read", Map.of("instanceKey", INSTANCE_VISIBLE, "version", "draft"), Map.of());
        assertFalse(denied.ok());
        assertEquals("NOT_FOUND", denied.content());

        // 有权限 → 读到草稿
        ToolProvider.ToolResult allowed = entityRead.invoke(ctx(agentPrincipal("ontology.read_draft")),
                "av1", "entity.read", Map.of("instanceKey", INSTANCE_VISIBLE, "version", "draft"), Map.of());
        assertTrue(allowed.ok(), () -> "有 read_draft 权限应读到草稿: " + allowed.content());
        assertTrue(allowed.content().contains("DRAFT"));
    }

    @Test
    void modelQueryAndCoverageFollowAcl() {
        ModelQueryToolProvider modelQuery = new ModelQueryToolProvider(queries, valueIndex, mapper,
                ctx2 -> ctx2 == null ? null : ctx2.principal());
        Map<String, Object> args = Map.of("instanceKey", INSTANCE_VISIBLE);
        ToolProvider.ToolResult ok = modelQuery.invoke(ctx(agentPrincipal()), "av1", "model.query", args, Map.of());
        assertTrue(ok.ok(), ok.content());
        assertEquals("CONFIDENTIAL", ok.dataLabel());

        ToolProvider.ToolResult denied = modelQuery.invoke(ctx(agentPrincipal()), "av1", "model.query",
                Map.of("instanceKey", INSTANCE_HIDDEN), Map.of());
        assertFalse(denied.ok());
        assertEquals("NOT_FOUND", denied.content());

        ModelCoverageToolProvider coverage = new ModelCoverageToolProvider(queries,
                new CoverageService(new com.tioka.ontology.application.def.DefinitionService(mapper, null),
                        valueIndex, mapper, emptyProvider(), topic -> null),
                mapper, ctx2 -> ctx2 == null ? null : ctx2.principal());
        ToolProvider.ToolResult covDenied = coverage.invoke(ctx(agentPrincipal()), "av1", "model.coverage",
                Map.of("instanceKey", INSTANCE_HIDDEN), Map.of());
        assertFalse(covDenied.ok());
        assertEquals("NOT_FOUND", covDenied.content());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return (org.springframework.beans.factory.ObjectProvider<T>) org.mockito.Mockito.mock(
                org.springframework.beans.factory.ObjectProvider.class);
    }
}
