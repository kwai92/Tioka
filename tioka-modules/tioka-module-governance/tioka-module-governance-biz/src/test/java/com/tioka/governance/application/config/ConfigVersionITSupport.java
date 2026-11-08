package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.framework.persistence.UtcLocalDateTimeTypeHandler;
import com.tioka.governance.infrastructure.config.CfgStore;
import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.mapper.CfgDraftMapper;
import com.tioka.governance.infrastructure.mapper.CfgProjectionPointerMapper;
import com.tioka.governance.infrastructure.mapper.CfgVersionMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.LocalDateTime;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * 双实例共享 PG + Redis 的 IT 夹具（MyBatis；不经 DualInstance / 不碰 K4）。
 */
final class ConfigVersionITSupport {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_cfg")
            .withUsername("tioka")
            .withPassword("tioka");

    static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7.2-alpine"));

    private static final SqlSessionFactory SQL_SESSION_FACTORY;

    static {
        PG.start();
        REDIS.start();
        migrate(dataSource());
        try {
            SQL_SESSION_FACTORY = buildSqlSessionFactory(dataSource());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ConfigVersionITSupport() {
    }

    static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    static SqlSessionFactory buildSqlSessionFactory(DataSource ds) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/Cfg*.xml"));
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().getTypeHandlerRegistry()
                .register(LocalDateTime.class, UtcLocalDateTimeTypeHandler.class);
        return factory;
    }

    static void migrate(DataSource ds) {
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cfg_draft (
                        object_type     VARCHAR(64)  NOT NULL,
                        object_id       VARCHAR(64)  NOT NULL,
                        spec            JSONB        NOT NULL,
                        revision        BIGINT       NOT NULL DEFAULT 0,
                        based_on_version BIGINT,
                        update_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        update_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
                        PRIMARY KEY (object_type, object_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cfg_version (
                        version_id       VARCHAR(64)  PRIMARY KEY,
                        object_type      VARCHAR(64)  NOT NULL,
                        object_id        VARCHAR(64)  NOT NULL,
                        version_no       INT          NOT NULL,
                        spec             JSONB        NOT NULL,
                        content_hash     CHAR(64)     NOT NULL,
                        status           VARCHAR(32)  NOT NULL,
                        publish_note     VARCHAR(512),
                        published_by     VARCHAR(64),
                        published_at     TIMESTAMPTZ,
                        previous_version_id VARCHAR(64),
                        UNIQUE (object_type, object_id, version_no)
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

    static Instance newInstance(String actorId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        // SqlSessionTemplate 线程安全，供 Redis 监听线程与主线程共用
        SqlSessionTemplate template = new SqlSessionTemplate(SQL_SESSION_FACTORY);
        CfgStore store = new CfgStore(
                template.getMapper(CfgDraftMapper.class),
                template.getMapper(CfgVersionMapper.class),
                template.getMapper(CfgChangeMapper.class),
                template.getMapper(CfgProjectionPointerMapper.class));
        ConfigSpecNormalizer normalizer = new ConfigSpecNormalizer(mapper);
        ConfigProjectionCache cache = new ConfigProjectionCache();

        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        container.start();

        ConfigCurrentPointerStore pointers = new ConfigCurrentPointerStore(redis);
        ConfigVersionRefreshBus bus = new ConfigVersionRefreshBus(
                redis, mapper, container, cache, pointers, store, normalizer);
        bus.subscribe();
        ConfigVersionReconciler reconciler = new ConfigVersionReconciler(cache, pointers, bus);
        ConfigVersionApi api = new DefaultConfigVersionService(
                store, normalizer, cache, pointers, bus, mapper, null, actorId);
        return new Instance(api, cache, reconciler, bus, container, factory);
    }

    record Instance(
            ConfigVersionApi api,
            ConfigProjectionCache cache,
            ConfigVersionReconciler reconciler,
            ConfigVersionRefreshBus bus,
            RedisMessageListenerContainer container,
            LettuceConnectionFactory factory) implements AutoCloseable {
        @Override
        public void close() {
            try {
                bus.unsubscribe();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                container.stop();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                factory.destroy();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
