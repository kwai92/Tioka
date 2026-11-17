package com.tioka.governance.application.alert;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.redis.testcontainers.RedisContainer;
import com.tioka.framework.persistence.UtcLocalDateTimeTypeHandler;
import com.tioka.governance.api.alert.AlertComparator;
import com.tioka.governance.api.alert.AlertConditionType;
import com.tioka.governance.api.alert.AlertRuleRef;
import com.tioka.governance.api.alert.AlertSignalProvider;
import com.tioka.governance.api.alert.AlertSignalProvider.AlertObservation;
import com.tioka.governance.infrastructure.alert.AlertChangeLog;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.mapper.OpsAlertEventMapper;
import com.tioka.governance.infrastructure.mapper.OpsAlertRuleMapper;
import com.tioka.governance.infrastructure.persistence.CfgChangeDO;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import com.tioka.system.api.notify.NotificationPort;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GOV-13 告警端到端（ADR-041 §5.3；验收 {@code AlertRuleIT}）：真实 PG + Redis。
 *
 * <p>覆盖：内置规则播种 → 求值落库 → 通知外发 → 状态去重 → 恢复通知 → {@code cfg_change} 流水。
 * Redis 用于求值互斥锁（SETNX），验证加锁—求值—释放闭环。
 */
class AlertRuleIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_alert")
            .withUsername("tioka")
            .withPassword("tioka");

    static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7.2-alpine"));

    private static SqlSessionTemplate sessions;
    private static LettuceConnectionFactory redisFactory;
    private static OpsAlertRepository repo;
    private static AlertRuleService ruleService;
    private static AlertEvaluator evaluator;
    private static CfgChangeMapper changes;
    private static FakeQuotaProvider provider;
    private static final List<NotificationPort.NotificationCommand> sent = new ArrayList<>();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUp() throws Exception {
        PG.start();
        REDIS.start();
        migrate(dataSource());

        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource());
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/OpsAlert*.xml"));
        bean.setConfiguration(new MybatisConfiguration());
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().getTypeHandlerRegistry()
                .register(LocalDateTime.class, UtcLocalDateTimeTypeHandler.class);
        factory.getConfiguration().addMapper(OpsAlertRuleMapper.class);
        factory.getConfiguration().addMapper(OpsAlertEventMapper.class);
        factory.getConfiguration().addMapper(CfgChangeMapper.class);
        sessions = new SqlSessionTemplate(factory);

        repo = new OpsAlertRepository(sessions.getMapper(OpsAlertRuleMapper.class),
                sessions.getMapper(OpsAlertEventMapper.class));
        changes = sessions.getMapper(CfgChangeMapper.class);

        AlertProperties props = new AlertProperties();
        props.setDefaultRecipients(List.of("ops@tioka"));

        ruleService = new AlertRuleService(repo, new AlertChangeLog(changes), props, null,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory = new LettuceConnectionFactory(conf);
        redisFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider<NotificationPort> notifyProvider = mock(ObjectProvider.class);
        when(notifyProvider.getIfAvailable()).thenReturn(sent::add);

        provider = new FakeQuotaProvider();
        evaluator = new AlertEvaluator(repo, ruleService, List.of(provider), new AlertEngine(),
                new AlertEmitter(repo, notifyProvider, props), redisProvider, props, null);
    }

    @AfterAll
    static void tearDown() {
        if (redisFactory != null) {
            redisFactory.destroy();
        }
        REDIS.stop();
        PG.stop();
    }

    @Test
    @DisplayName("播种内置规则 → 命中落库并通知 → 去重 → 恢复通知 → cfg_change 流水")
    void endToEndFiresDedupsAndResolves() {
        provider.utilization = 0.95;
        evaluator.evaluate();

        assertEquals(4, repo.listAllRules().size(), "内置默认规则应播种四条");
        List<OpsAlertEventDO> firing = repo.listRecentEvents(null, null, "FIRING", 50);
        assertEquals(1, firing.size(), "仅配额预警命中");
        assertEquals("QUOTA_WARN", firing.get(0).getConditionType());
        assertEquals("ws_1", firing.get(0).getTargetId());
        assertEquals(1, sent.size(), "命中应外发一条通知");
        assertEquals("alert.firing", sent.get(0).template());

        evaluator.evaluate();
        assertEquals(1, repo.listRecentEvents(null, null, "FIRING", 50).size(), "状态未变不重复发");

        provider.utilization = 0.1;
        evaluator.evaluate();
        List<OpsAlertEventDO> resolved = repo.listRecentEvents(null, null, "RESOLVED", 50);
        assertEquals(1, resolved.size(), "回落到阈值内应发恢复");
        assertEquals("alert.resolved", sent.get(sent.size() - 1).template());

        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        LocalDateTime to = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);
        List<CfgChangeDO> ledger = changes.listByRange(from, to);
        assertTrue(ledger.stream().anyMatch(c -> AlertChangeLog.OBJECT_TYPE.equals(c.getObjectType())
                && "builtin".equals(c.getReason())), "内置规则播种须写 cfg_change 流水");
    }

    @Test
    @DisplayName("健康状态条件无信号源时不产生事件（软降级）")
    void missingProviderProducesNoEvent() {
        evaluator.evaluate();
        assertTrue(repo.listRecentEvents(null, null, null, 500).stream()
                .noneMatch(e -> "HEALTH_STATUS".equals(e.getConditionType())),
                "无信号源时不得产生健康状态事件");
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
                    CREATE TABLE IF NOT EXISTS ops_alert_rule (
                        id              VARCHAR(32)  PRIMARY KEY,
                        name            VARCHAR(128) NOT NULL,
                        target_type     VARCHAR(16)  NOT NULL,
                        target_id       VARCHAR(64),
                        condition_type  VARCHAR(24)  NOT NULL,
                        comparator      VARCHAR(4)   NOT NULL,
                        threshold_value DOUBLE PRECISION,
                        threshold_text  VARCHAR(64),
                        window_minutes  INT          NOT NULL DEFAULT 10,
                        severity        VARCHAR(16)  NOT NULL,
                        channels        VARCHAR(128) NOT NULL DEFAULT 'in_app',
                        recipients      VARCHAR(512),
                        silence_start   VARCHAR(5),
                        silence_end     VARCHAR(5),
                        enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
                        builtin         BOOLEAN      NOT NULL DEFAULT FALSE,
                        last_fired_at   TIMESTAMPTZ,
                        description     TEXT,
                        create_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        update_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        create_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
                        update_by       VARCHAR(64)  NOT NULL DEFAULT 'system'
                    )
                    """);
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_rule_builtin_name "
                    + "ON ops_alert_rule (name) WHERE builtin");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS ops_alert_event (
                        id              VARCHAR(32)  PRIMARY KEY,
                        rule_id         VARCHAR(32)  NOT NULL,
                        rule_name       VARCHAR(128) NOT NULL,
                        target_type     VARCHAR(16)  NOT NULL,
                        target_id       VARCHAR(64),
                        condition_type  VARCHAR(24)  NOT NULL,
                        state           VARCHAR(16)  NOT NULL,
                        severity        VARCHAR(16)  NOT NULL,
                        metric_value    DOUBLE PRECISION,
                        threshold_value DOUBLE PRECISION,
                        message         TEXT,
                        dedup_key       VARCHAR(160) NOT NULL,
                        notified        BOOLEAN      NOT NULL DEFAULT FALSE,
                        observed_at     TIMESTAMPTZ  NOT NULL,
                        create_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cfg_change (
                        id           BIGSERIAL PRIMARY KEY,
                        object_type  VARCHAR(64)  NOT NULL,
                        target_key   VARCHAR(128) NOT NULL,
                        before_json  JSONB,
                        after_json   JSONB,
                        actor_id     VARCHAR(64)  NOT NULL,
                        reason       VARCHAR(512),
                        created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        trace_id     VARCHAR(64)
                    )
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("migrate failed", e);
        }
    }

    /** 只支持配额条件的信号源；{@code utilization} 可变以驱动命中 / 恢复。 */
    static final class FakeQuotaProvider implements AlertSignalProvider {
        volatile double utilization;

        @Override
        public boolean supports(AlertConditionType condition) {
            return condition == AlertConditionType.QUOTA_WARN
                    || condition == AlertConditionType.QUOTA_EXHAUSTED;
        }

        @Override
        public List<AlertObservation> observe(AlertRuleRef rule, Instant now) {
            return List.of(AlertObservation.ofValue("ws_1", utilization,
                    "供给侧利用率 " + utilization, now));
        }
    }
}
