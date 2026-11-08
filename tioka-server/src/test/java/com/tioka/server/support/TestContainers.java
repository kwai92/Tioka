package com.tioka.server.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * JVM 内单例容器：所有 IT 共享 PostgreSQL 16（pgvector）与 Redis 7。
 * RocketMQ（NameServer + Broker）懒启动，仅 {@code @Tag("mq")} / G2 闸门冒烟拉起。
 */
public final class TestContainers {
    private static final DockerImageName ROCKETMQ_IMAGE =
            DockerImageName.parse("apache/rocketmq:5.3.1");

    public static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("tioka")
                    .withUsername("tioka")
                    .withPassword("tioka")
                    .withCommand("postgres", "-c", "max_connections=200");

    public static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    /** 与 {@code deploy/compose.yml} 同版本；调用 {@link RocketMqCluster#ensureStarted()} 才启动。 */
    public static final RocketMqCluster ROCKETMQ = new RocketMqCluster();

    static {
        Startables.deepStart(PG, REDIS).join();
    }

    private TestContainers() {}

    public static void register(DynamicPropertyRegistry r) {
        // M0-P2：与 application.yml 一致，会话 TimeZone=UTC（Testcontainers URL 会覆盖 yml）
        r.add("spring.datasource.url", () -> appendUtcSession(PG.getJdbcUrl()));
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    public static void registerRocketMq(DynamicPropertyRegistry r) {
        ROCKETMQ.ensureStarted();
        r.add("tioka.messaging.mode", () -> "rocketmq");
        r.add("tioka.messaging.name-server", ROCKETMQ::nameServerAddr);
        r.add("rocketmq.name-server", ROCKETMQ::nameServerAddr);
    }

    static String appendUtcSession(String jdbcUrl) {
        String sep = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + sep + "options=-c%20TimeZone%3DUTC";
    }

    /**
     * RocketMQ 5.3.1 NameServer + Broker（共享 Docker network）。
     * 懒启动：不占用默认 IT / 非 mq 闸门的内存与启动时间。
     */
    public static final class RocketMqCluster {
        private final Object lock = new Object();
        private volatile boolean started;
        private Network network;
        private GenericContainer<?> namesrv;
        private GenericContainer<?> broker;

        public void ensureStarted() {
            if (started) {
                return;
            }
            synchronized (lock) {
                if (started) {
                    return;
                }
                network = Network.newNetwork();
                namesrv = new GenericContainer<>(ROCKETMQ_IMAGE)
                        .withNetwork(network)
                        .withNetworkAliases("rmqnamesrv")
                        .withExposedPorts(9876)
                        .withCommand("sh", "mqnamesrv")
                        .waitingFor(Wait.forLogMessage(".*The Name Server boot success.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(2)));
                namesrv.start();

                broker = new GenericContainer<>(ROCKETMQ_IMAGE)
                        .withNetwork(network)
                        .withNetworkAliases("broker-g2")
                        .withExposedPorts(10909, 10911, 10912)
                        .dependsOn(namesrv)
                        .withEnv("NAMESRV_ADDR", "rmqnamesrv:9876")
                        .withCommand("sh", "-c",
                                // 镜像 WORKDIR 为 .../bin，配置在 ../conf
                                "echo \"brokerIP1=$(hostname -i)\" >> ../conf/broker.conf && "
                                        + "echo \"autoCreateTopicEnable=true\" >> ../conf/broker.conf && "
                                        + "sh mqbroker -n rmqnamesrv:9876 -c ../conf/broker.conf")
                        .waitingFor(Wait.forLogMessage(".*The broker.*boot success.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(3)));
                broker.start();
                started = true;
            }
        }

        public String nameServerAddr() {
            ensureStarted();
            return namesrv.getHost() + ":" + namesrv.getMappedPort(9876);
        }

        public int nameServerPort() {
            ensureStarted();
            return namesrv.getMappedPort(9876);
        }

        public String nameServerHost() {
            ensureStarted();
            return namesrv.getHost();
        }

        public boolean isStarted() {
            return started;
        }
    }
}
