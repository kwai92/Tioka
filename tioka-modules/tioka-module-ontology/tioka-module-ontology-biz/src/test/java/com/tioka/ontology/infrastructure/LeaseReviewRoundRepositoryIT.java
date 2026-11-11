package com.tioka.ontology.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tioka.ontology.infrastructure.mapper.LeaseReviewRoundMapper;
import com.tioka.ontology.infrastructure.persistence.LeaseReviewRoundDO;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lease_review_round}（V175）仓储 PG 集成测试（做法同 FoldSchedulerIT）：
 * upsert 幂等覆盖、复合主键查询、maxRound / currentRound。
 */
class LeaseReviewRoundRepositoryIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_review_round")
            .withUsername("tioka")
            .withPassword("tioka");

    static LeaseReviewRoundRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE lease_review_round (
                        review_run_id          VARCHAR(64)  NOT NULL,
                        round                  INTEGER      NOT NULL,
                        tioka_run_id           VARCHAR(64),
                        model_version          VARCHAR(64)  NOT NULL,
                        checkpoint_set_version VARCHAR(64)  NOT NULL,
                        status                 VARCHAR(32)  NOT NULL,
                        stats_json             JSONB        NOT NULL,
                        PRIMARY KEY (review_run_id, round)
                    )
                    """);
        }
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/LeaseReviewRoundMapper.xml"));
        bean.setConfiguration(new MybatisConfiguration());
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().addMapper(LeaseReviewRoundMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        repository = new LeaseReviewRoundRepository(template.getMapper(LeaseReviewRoundMapper.class));
    }

    @AfterAll
    static void tearDown() {
        PG.stop();
    }

    private static LeaseReviewRoundDO row(String runId, int round, String status) {
        LeaseReviewRoundDO r = new LeaseReviewRoundDO();
        r.setReviewRunId(runId);
        r.setRound(round);
        r.setTiokaRunId("run-" + round);
        r.setModelVersion("m1.0");
        r.setCheckpointSetVersion("cp-1");
        r.setStatus(status);
        r.setStatsJson("{\"mandatoryFailed\": 1}");
        return r;
    }

    @Test
    void upsert幂等覆盖与查询() {
        repository.upsert(row("rr-1", 1, "RUNNING"));
        repository.upsert(row("rr-1", 1, "COMPLETED")); // 同 (run, round) 覆盖

        Optional<LeaseReviewRoundDO> found = repository.find("rr-1", 1);
        assertTrue(found.isPresent());
        assertEquals("COMPLETED", found.get().getStatus());
        assertEquals("{\"mandatoryFailed\": 1}", found.get().getStatsJson());
        assertEquals(0, repository.currentRound("rr-unknown"));
    }

    @Test
    void maxRound取当前轮次() {
        repository.upsert(row("rr-2", 1, "COMPLETED"));
        repository.upsert(row("rr-2", 2, "RUNNING"));
        assertEquals(2, repository.currentRound("rr-2"));
    }
}
