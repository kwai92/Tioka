package com.tioka.server.gate.support;

import io.lettuce.core.RedisURI;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.application.RuntimeTestHooks;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import com.tioka.server.TiokaServerApplication;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestContainers;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ENG-04b / ENG-04c：同一 JVM 内 N 个 Spring 上下文，共享 Testcontainers PG/Redis（可选 Keycloak）与 LLM/MCP 桩。
 * ENG-04c：{@link #stop(String)} / {@link #start(String)} / {@link #restart(String)} 按名称停启；
 * 与 {@link #crash(int)}（租约不释放、槽位不复活）分离。
 */
public final class GateCluster implements AutoCloseable {
    private final List<ConfigurableApplicationContext> instances;
    private final Map<String, Object> sharedProps;
    private final LlmStubServer llm;
    private final McpStubServer mcp;
    private final KeycloakGateSupport keycloak;
    private final boolean ownKeycloak;

    private GateCluster(List<ConfigurableApplicationContext> instances,
                        Map<String, Object> sharedProps,
                        LlmStubServer llm,
                        McpStubServer mcp,
                        KeycloakGateSupport keycloak,
                        boolean ownKeycloak) {
        this.instances = instances;
        this.sharedProps = sharedProps;
        this.llm = llm;
        this.mcp = mcp;
        this.keycloak = keycloak;
        this.ownKeycloak = ownKeycloak;
    }

    public static GateCluster start(int n) {
        return start(n, "three-tools", Map.of(), true);
    }

    public static GateCluster start(int n, String llmScript, Map<String, Object> extraProps, boolean withKeycloak) {
        if (n < 1) {
            throw new IllegalArgumentException("n >= 1");
        }
        TestContainers.PG.getJdbcUrl();
        System.setProperty("tioka.llm.script", llmScript == null ? "three-tools" : llmScript);
        LlmStubServer llm = LlmStubServer.start();
        McpStubServer mcp = McpStubServer.start();
        KeycloakGateSupport keycloak = withKeycloak ? KeycloakGateSupport.start() : null;

        Map<String, Object> shared = baseProps(llm.baseUrl(), mcp.baseUrl(), keycloak);
        if (extraProps != null) {
            shared.putAll(extraProps);
        }

        List<ConfigurableApplicationContext> ctxs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = Character.toString((char) ('A' + i));
            ctxs.add(run(withInstance(shared, id)));
        }
        GateCluster cluster = new GateCluster(ctxs, Map.copyOf(shared), llm, mcp, keycloak, withKeycloak);
        // C-12 启动哨兵：fail-fast 发现游离 dispatcher（僵尸 @SpringBootTest 上下文抢消费 rt:turns）。
        // 抛出前必须先关停本集群，否则刚启动的实例自身泄漏成新僵尸，产生级联误伤。
        List<String> rogues = cluster.detectRogueConsumers();
        if (!rogues.isEmpty()) {
            cluster.close();
            throw new IllegalStateException("GateCluster 哨兵：发现游离 dispatcher 消费者（僵尸上下文在抢消费 "
                    + TurnDispatchQueue.STREAM_KEY + "/" + TurnDispatchQueue.GROUP + "）" + rogues
                    + "；期望消费者 ⊆ " + cluster.expectedConsumers()
                    + "；存在游离 dispatcher 上下文，请排查缓存未关闭的 @SpringBootTest/集群实例");
        }
        return cluster;
    }

    public ConfigurableApplicationContext instance(int i) {
        return Objects.requireNonNull(instances.get(i), "instance " + i + " already stopped/crashed");
    }

    public int size() {
        return instances.size();
    }

    public int port(int i) {
        return portOf(instance(i));
    }

    public String baseUrl(int i) {
        return "http://127.0.0.1:" + port(i);
    }

    public ConfigurableApplicationContext a() { return instance(0); }

    public ConfigurableApplicationContext b() { return instance(1); }

    public int portA() { return port(0); }

    public int portB() { return port(1); }

    public String baseUrlA() { return baseUrl(0); }

    public String baseUrlB() { return baseUrl(1); }

    public LlmStubServer llm() { return llm; }

    public McpStubServer mcp() { return mcp; }

    public KeycloakGateSupport keycloak() {
        if (keycloak == null) {
            throw new IllegalStateException("本集群未启用 Keycloak");
        }
        return keycloak;
    }

    /**
     * 模拟崩溃：freeze → release-on-shutdown=false → 关闭上下文，租约不释放。
     */
    public void crash(int i) {
        ConfigurableApplicationContext ctx = instances.get(i);
        if (ctx == null) {
            throw new IllegalStateException("instance " + i + " already crashed");
        }
        RuntimeTestHooks hooks = ctx.getBean(RuntimeTestHooks.class);
        hooks.freeze();
        ctx.getBean(KernelProperties.class).setReleaseOnShutdown(false);
        ctx.close();
        instances.set(i, null);
        removeConsumers(List.of(slotId(i)));
    }

    /** 按槽位优雅停止（释放租约），槽位置空；可用 {@link #startInstance(int)} 复活。 */
    public void stopInstance(int i) {
        ConfigurableApplicationContext ctx = instances.get(i);
        if (ctx == null) {
            throw new IllegalStateException("instance " + i + " is not running");
        }
        ctx.close();
        instances.set(i, null);
        removeConsumers(List.of(slotId(i)));
    }

    /** 按 {@code tioka.runtime.instance-id}（A/B/…）优雅停止。 */
    public void stop(String instanceId) {
        stopInstance(indexOf(instanceId));
    }

    /** 按槽位启动（仅当已 stop/crash 为空槽时）。 */
    public void startInstance(int i) {
        if (i < 0 || i >= instances.size()) {
            throw new IllegalArgumentException("instance index out of range: " + i);
        }
        if (instances.get(i) != null) {
            throw new IllegalStateException("instance " + i + " is already running");
        }
        String id = Character.toString((char) ('A' + i));
        instances.set(i, run(withInstance(sharedProps, id)));
        List<String> rogues = detectRogueConsumers();
        if (!rogues.isEmpty()) {
            // 本集群由调用方 try-with-resources 持有，会随用例失败关闭，这里直接 fail-fast
            throw new IllegalStateException("GateCluster 哨兵：发现游离 dispatcher 消费者（僵尸上下文在抢消费 "
                    + TurnDispatchQueue.STREAM_KEY + "/" + TurnDispatchQueue.GROUP + "）" + rogues
                    + "；期望消费者 ⊆ " + expectedConsumers());
        }
    }

    /** 按名称启动已停止的实例。 */
    public void startInstance(String instanceId) {
        startInstance(indexOf(instanceId));
    }

    /**
     * ENG-04c：按槽位优雅重启（释放租约）→ 同 {@code instance-id} 重建应用上下文。
     */
    public void restart(int i) {
        stopInstance(i);
        startInstance(i);
    }

    /** 按 {@code tioka.runtime.instance-id}（A/B/…）重启。 */
    public void restart(String instanceId) {
        restart(indexOf(instanceId));
    }

    private int indexOf(String instanceId) {
        if (instanceId == null || instanceId.length() != 1) {
            throw new IllegalArgumentException("instanceId 须为单字母 A/B/…");
        }
        int i = instanceId.charAt(0) - 'A';
        if (i < 0 || i >= instances.size()) {
            throw new IllegalArgumentException("unknown instanceId: " + instanceId);
        }
        return i;
    }

    @Override
    public void close() {
        for (int i = instances.size() - 1; i >= 0; i--) {
            ConfigurableApplicationContext ctx = instances.get(i);
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                }
                instances.set(i, null);
            }
        }
        // C-12：全部上下文关闭后再注销 consumer——若在关闭前删，存活的 dispatcher 线程
        // 会在下一轮 XREADGROUP（≤2s 阻塞读）把 consumer 重新注册回去
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < instances.size(); i++) {
            ids.add(slotId(i));
        }
        removeConsumers(ids);
        try {
            mcp.close();
        } finally {
            try {
                llm.close();
            } finally {
                if (ownKeycloak && keycloak != null) {
                    keycloak.close();
                }
                System.clearProperty("tioka.llm.script");
            }
        }
    }

    private static ConfigurableApplicationContext run(Map<String, Object> props) {
        SpringApplication app = new SpringApplication(TiokaServerApplication.class);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("gate-cluster", props));
        app.setEnvironment(env);
        app.setAdditionalProfiles("gate");
        return app.run();
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getProperty("local.server.port", Integer.class, 0);
    }

    private static Map<String, Object> baseProps(String llmBaseUrl, String mcpBaseUrl, KeycloakGateSupport keycloak) {
        Map<String, Object> m = new HashMap<>();
        m.put("server.port", 0);
        m.put("spring.main.allow-bean-definition-overriding", "true");
        m.put("spring.datasource.url", TestContainers.PG.getJdbcUrl());
        m.put("spring.datasource.username", TestContainers.PG.getUsername());
        m.put("spring.datasource.password", TestContainers.PG.getPassword());
        m.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        m.put("spring.datasource.hikari.maximum-pool-size", "16");
        m.put("spring.datasource.hikari.minimum-idle", "2");
        m.put("spring.data.redis.host", TestContainers.REDIS.getHost());
        m.put("spring.data.redis.port", TestContainers.REDIS.getMappedPort(6379));
        m.put("spring.flyway.enabled", true);
        m.put("spring.sql.init.mode", "never");
        m.put("tioka.bootstrap.admin-password", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD);
        m.put("tioka.home", System.getProperty("java.io.tmpdir") + "/tioka-gate-home");
        m.put("tioka.model.mock", false);
        m.put("tioka.mcp.mock", false);
        m.put("tioka.egress.mode", "OPEN");
        m.put("tioka.gate.llm-base-url", llmBaseUrl);
        m.put("tioka.gate.mcp-base-url", mcpBaseUrl);
        m.put("tioka.runtime.executor.max-concurrent-turns", 128);
        m.put("tioka.runtime.executor.queue-capacity", 512);
        // 闸门 profile：租约 TTL 5s，便于 crash 后观察过期；回收扫描加速
        m.put("tioka.runtime.lease-ttl", "5s");
        m.put("tioka.runtime.lease-renew-interval", "2s");
        m.put("tioka.runtime.lease-reaper-interval-ms", "1000");
        m.put("tioka.runtime.release-on-shutdown", true);
        m.put("tioka.runtime.dispatcher-enabled", true);
        m.put("tioka.runtime.queued-sweeper-enabled", true);
        m.put("spring.flyway.placeholders.embeddingDim", "32");
        m.put("tioka.knowledge.embedding-dim", 32);
        m.put("tioka.knowledge.reindex-enabled", false);
        // ENG-04d：双实例指标端点供 PrometheusScrapeSupport / MH-10 / FLW-14
        m.put("management.endpoints.web.exposure.include", "health,info,metrics,prometheus");
        m.put("management.endpoint.metrics.enabled", true);
        m.put("management.endpoint.prometheus.enabled", true);
        if (keycloak != null) {
            m.put("tioka.auth.oidc.frontend-base-url", "http://127.0.0.1:5173");
            m.put("tioka.auth.oidc.providers.keycloak.issuer-uri", keycloak.issuerUri());
            m.put("tioka.auth.oidc.providers.keycloak.client-id", KeycloakGateSupport.CLIENT_ID);
            m.put("tioka.auth.oidc.providers.keycloak.client-secret", KeycloakGateSupport.CLIENT_SECRET);
            m.put("tioka.auth.oidc.providers.keycloak.scopes[0]", "openid");
            m.put("tioka.auth.oidc.providers.keycloak.scopes[1]", "profile");
            m.put("tioka.auth.oidc.providers.keycloak.scopes[2]", "email");
            m.put("tioka.auth.oidc.providers.keycloak.groups-claim", "groups");
            m.put("tioka.auth.oidc.providers.keycloak.auto-provision", true);
            m.put("tioka.auth.oidc.providers.keycloak.group-role-mappings[0].group", "/tioka/admins");
            m.put("tioka.auth.oidc.providers.keycloak.group-role-mappings[0].system-role", "system_admin");
            m.put("tioka.auth.oidc.providers.keycloak.group-role-mappings[1].group", "/tioka/sales");
            m.put("tioka.auth.oidc.providers.keycloak.group-role-mappings[1].workspace", "ws_sales");
            m.put("tioka.auth.oidc.providers.keycloak.group-role-mappings[1].role", "member");
            m.put("tioka.auth.oidc.resource-server.enabled", true);
            m.put("tioka.auth.oidc.resource-server.audiences[0]", "tioka-api");
        }
        return m;
    }

    private static Map<String, Object> withInstance(Map<String, Object> shared, String id) {
        Map<String, Object> m = new HashMap<>(shared);
        m.put("tioka.runtime.instance-id", id);
        m.put("spring.application.name", "tioka-" + id);
        return m;
    }

    private static String slotId(int i) {
        return Character.toString((char) ('A' + i));
    }

    private Set<String> expectedConsumers() {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < instances.size(); i++) {
            expected.add(slotId(i));
        }
        return expected;
    }

    /**
     * C-12 启动哨兵：XINFO CONSUMERS 校验 {@code rt:turns}/{@code rt-workers} 内没有本集群之外的消费者。
     * 游离 dispatcher 上下文（@SpringBootTest 缓存的僵尸上下文，instance-id=local/HOSTNAME）
     * 若在本次 GateCluster 启动前已存在，则返回其名单（空 = 通过）。
     * 已知边界：僵尸若在本集群启动之后才被缓存创建，哨兵无法覆盖（由 G2 覆写治理兜底）。
     */
    private List<String> detectRogueConsumers() {
        ConfigurableApplicationContext ctx = instances.stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (ctx == null) {
            return List.of();
        }
        try {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            byte[] key = TurnDispatchQueue.STREAM_KEY.getBytes(StandardCharsets.UTF_8);
            StreamInfo.XInfoConsumers info = redis.execute((RedisCallback<StreamInfo.XInfoConsumers>) connection ->
                    connection.streamCommands().xInfoConsumers(key, TurnDispatchQueue.GROUP));
            Set<String> expected = expectedConsumers();
            List<String> all = new ArrayList<>();
            List<String> rogues = new ArrayList<>();
            for (StreamInfo.XInfoConsumer c : info) {
                all.add(c.consumerName());
                if (!expected.contains(c.consumerName())) {
                    rogues.add(c.consumerName() + "(idle=" + c.idleTimeMs() + "ms)");
                }
            }
            if (!rogues.isEmpty()) {
                System.err.println("[GateCluster] 当前全部消费者 " + all + "，期望 ⊆ " + expected);
            }
            return rogues;
        } catch (Exception e) {
            // 哨兵自身故障不应阻塞测试启动（如消费组尚未创建的瞬时态）
            System.err.println("[GateCluster] 启动哨兵跳过: " + e);
            return List.of();
        }
    }

    /**
     * C-12：经独立 Lettuce 连接注销 consumer（必须在对应上下文 {@code close()} 之后调用——
     * 关闭前删会被存活的 dispatcher 线程在下一轮 XREADGROUP 重新注册回去）。
     * 不复用实例的连接工厂：上下文关闭后其连接工厂随之销毁，无法再执行清理。
     */
    private static void removeConsumers(List<String> consumerNames) {
        if (consumerNames.isEmpty()) {
            return;
        }
        try {
            RedisURI uri = RedisURI.builder()
                    .withHost(TestContainers.REDIS.getHost())
                    .withPort(TestContainers.REDIS.getMappedPort(6379))
                    .build();
            try (RedisClient client = RedisClient.create(uri);
                 StatefulRedisConnection<String, String> conn = client.connect()) {
                for (String name : consumerNames) {
                    try {
                        conn.sync().xgroupDelconsumer(TurnDispatchQueue.STREAM_KEY,
                                io.lettuce.core.Consumer.from(TurnDispatchQueue.GROUP, name));
                    } catch (Exception e) {
                        System.err.println("[GateCluster] 注销 consumer " + name + " 失败（忽略）: " + e);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GateCluster] 独立 Redis 清理连接失败（忽略）: " + e);
        }
    }
}
