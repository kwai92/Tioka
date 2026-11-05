package com.tioka.server.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM 内单例容器：所有 IT 共享 PostgreSQL 16（pgvector）与 Redis 7。
 */
public final class TestContainers {
    public static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("tioka")
                    .withUsername("tioka")
                    .withPassword("tioka");

    public static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static {
        Startables.deepStart(PG, REDIS).join();
    }

    private TestContainers() {}

    public static void register(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
