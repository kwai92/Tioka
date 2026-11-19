package com.tioka.server.gate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * GF 闸门跨仓容器拓扑 spike（F-00 §6.1 `GfTiokaModeGateIT` 的**前置可行性验证**）。
 *
 * <p>背景：`GfTiokaModeGateIT` 要断言「Tioka 的 G1 权限闸门在 Foundation 模式下全部通过」，
 * 需要 Tioka 应用与**真实 Foundation 中台服务端**同环境。但主仓库不依赖 `foundation-server`，
 * 主仓 gate job 也只起 Keycloak（`KeycloakGateSupport`），不起中台；Foundation 子仓 CI 起中台
 * 却不包含 Tioka 代码。两仓 CI 拓扑都不具备合体条件。
 *
 * <p>本用例**不启动 Tioka 应用上下文**，只验证最小可运行拓扑：以容器方式起 `foundation-server`
 * 可执行 jar，断言三件事——
 * <ol>
 *   <li>jar 可执行（子仓 `foundation-server/pom.xml` 补 `repackage` 后产出 fat jar，此前是 47KB 普通 jar）；</li>
 *   <li>应用内 Flyway 全量迁移成功（33 个 SQL，含 `V1__foundation_schema.sql` 的 pgvector 扩展）；</li>
 *   <li>健康端点与聚合 `/actuator/health` 均 UP（后者含 db / redis 指示器，证明依赖连通）。</li>
 * </ol>
 *
 * <p>刻意只起 PostgreSQL + Redis 两个依赖：`minimal` profile 只装配 common / sequence 域，
 * 不含 notify / file / mq，故无需 RocketMQ 与 MinIO；且 `foundation-server` 无启动期 Keycloak
 * 强校验（`KeycloakClientConfiguration` 仅声明 Bean，不取令牌），故本用例也不需要 Keycloak。
 * 与 Tioka gate 既有容器（`TestContainers` 的 tioka 库、`KeycloakGateSupport` 的 tioka realm）
 * 完全隔离，不存在双库或多 realm 冲突。
 *
 * <p>运行：{@code mvn -B verify -Pgate}（surefire 的 gate profile 收录所有 IT 后缀用例且
 * {@code groups=gate}）。本地无 Docker 时 Testcontainers 会失败，以 CI 为权威。
 *
 * <p>若 `foundation-server` 的 fat jar 不存在（未先构建子仓），按项目纪律用
 * {@link Assumptions} 跳过而非失败——环境限制不等于代码缺陷，门禁须能自动区分二者。
 */
@Tag("gate")
@DisplayName("GF 跨仓容器拓扑 spike：主仓 gate 内以容器起 foundation-server")
class GfFoundationContainerTopologyIT {

    /** 与 deploy/Dockerfile.foundation 同基础镜像，保证「CI 验证的」与「交付的」一致。 */
    private static final DockerImageName JRE_IMAGE = DockerImageName.parse("eclipse-temurin:17-jre");

    /**
     * 与 deploy/compose.yml 同版本镜像，保证「CI 验证的拓扑」与「交付的拓扑」一致。
     * 注：Foundation 迁移只建 pgcrypto / pg_trgm（postgres contrib 自带），并不使用 vector 类型，
     * 故技术上裸 postgres:16 亦可；仍选 pgvector 镜像是为了与 compose 逐字对齐，避免两处漂移。
     */
    private static final DockerImageName PG_IMAGE = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static final String DB_NAME = "foundation";
    private static final String DB_USER = "foundation";
    private static final String DB_PASSWORD = "foundation";
    private static final int SERVER_PORT = 8081;

    private static Path serverJar;

    @BeforeAll
    static void locateServerJar() {
        serverJar = findServerJar();
        // 环境限制（未构建子仓）用 Assumptions 跳过，不让门禁永久红；CI 上 install 步骤必然产出该 jar。
        Assumptions.assumeTrue(serverJar != null,
                "未找到 foundation-server 可执行 jar，请先在 tioka-foundation 执行 mvn package；"
                        + "或用 -Dfoundation.server.jar=<绝对路径> 指定");
        System.out.println("[gf-topology] 使用 foundation-server jar: " + serverJar.toAbsolutePath()
                + " (" + (serverJar.toFile().length() / 1024 / 1024) + " MB)");
    }

    @Test
    @DisplayName("容器内 java -jar 可启动，Flyway 全量迁移成功，健康端点 UP")
    void foundationServerStartsAsContainerWithMigratedSchema() throws Exception {
        try (Network network = Network.newNetwork();
             PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(PG_IMAGE)
                     .withDatabaseName(DB_NAME)
                     .withUsername(DB_USER)
                     .withPassword(DB_PASSWORD)
                     // 限制连接数以压低 CI 内存（gate job 已并发多个容器）
                     .withCommand("postgres", "-c", "max_connections=60")
                     .withNetwork(network)
                     .withNetworkAliases("fdn-pg");
             GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE)
                     .withExposedPorts(6379)
                     .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)
                             .withStartupTimeout(Duration.ofMinutes(2)))
                     .withNetwork(network)
                     .withNetworkAliases("fdn-redis");
             // minimal profile：只装配 common / sequence，规避 notify / file / mq 对中间件的依赖
             GenericContainer<?> server = new GenericContainer<>(JRE_IMAGE)
                     .withNetwork(network)
                     .withExposedPorts(SERVER_PORT)
                     .withCopyFileToContainer(MountableFile.forHostPath(serverJar), "/app/app.jar")
                     // 显式限制堆，避免与 gate job 既有容器争内存（见类注释）
                     .withCommand("sh", "-c", "java -Xmx512m -XX:MaxMetaspaceSize=256m "
                             + "-Duser.timezone=UTC -jar /app/app.jar")
                     .withEnv("FOUNDATION_SERVER_PORT", String.valueOf(SERVER_PORT))
                     .withEnv("FOUNDATION_SERVER_PROFILE", "minimal")
                     .withEnv("FOUNDATION_DB_URL", "jdbc:postgresql://fdn-pg:5432/" + DB_NAME)
                     .withEnv("FOUNDATION_DB_USERNAME", DB_USER)
                     .withEnv("FOUNDATION_DB_PASSWORD", DB_PASSWORD)
                     // 应用内 Flyway 默认 enabled，故无需独立 migrate 容器
                     .withEnv("FOUNDATION_FLYWAY_ENABLED", "true")
                     .withEnv("FOUNDATION_REDIS_HOST", "fdn-redis")
                     .withEnv("FOUNDATION_REDIS_PORT", "6379")
                     // 默认 show-details=when-authorized，匿名请求拿不到 components 明细，
                     // 无法分辨健康失败来自 db/redis 还是 keycloak；spike 需精确定位故覆盖为 always。
                     .withEnv("MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS", "always")
                     .waitingFor(Wait.forHttp("/actuator/health/liveness")
                             .forPort(SERVER_PORT)
                             .forStatusCode(200)
                             .withStartupTimeout(Duration.ofMinutes(5)))) {

            pg.start();
            redis.start();
            server.start();

            System.out.println("[gf-topology] foundation-server 容器已启动，mapped port="
                    + server.getMappedPort(SERVER_PORT));

            // 1) liveness UP（Wait 策略已保证 200，此处显式断言语义）
            assertThatLivenessIsUp(server);

            // 2) 依赖连通性 —— 精确到 db 与 redis 两个组件，而**不看**聚合 status。
            //    原因：common 域的 KeycloakHealthIndicator 在 ping 失败时返回 down()，而本 spike
            //    刻意不起 Keycloak（minimal 不装配需 Keycloak 的域，且启动期无强校验），
            //    故聚合 /actuator/health 必然 DOWN 且 HTTP 状态码为 503（CI 实证）。
            //    若断言聚合 UP 或要求 200，会得到与拓扑无关的假失败——必须取 body 做组件级断言。
            HttpResponse<String> health = send(server, "/actuator/health");
            String aggregate = health.body();
            System.out.println("[gf-topology] /actuator/health HTTP " + health.statusCode()
                    + " = " + truncate(aggregate));
            assertComponentUp(aggregate, "db", server);
            assertComponentUp(aggregate, "redis", server);

            // 3) Flyway 全量迁移成功（33 个 SQL；迁移失败会让应用启动失败，此处再直接查表坐实）
            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                    pg.getHost(), pg.getMappedPort(5432), DB_NAME);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD);
                 Statement st = conn.createStatement()) {
                int applied;
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    if (!rs.next()) {
                        throw new AssertionError("flyway_schema_history 查询无结果");
                    }
                    applied = rs.getInt(1);
                }
                System.out.println("[gf-topology] Flyway 成功迁移条数=" + applied);
                if (applied < 33) {
                    throw new AssertionError("Flyway 迁移未全量执行，成功条数=" + applied + "（期望 >= 33）");
                }
                // 基线表存在性：与子仓 GfSmokeIT 同一判据，跨仓口径一致
                try (ResultSet rs = st.executeQuery("SELECT to_regclass('public.fdn_outbox')")) {
                    rs.next();
                    String regclass = rs.getString(1);
                    System.out.println("[gf-topology] fdn_outbox regclass=" + regclass);
                    if (regclass == null) {
                        throw new AssertionError("基线表 fdn_outbox 不存在，迁移未落到目标库");
                    }
                }
            }
        }
    }

    /**
     * 发请求并返回原始响应，**不校验状态码**。
     *
     * <p>为什么不在这里统一要求 200：聚合 {@code /actuator/health} 在有组件 DOWN 时返回 **503**，
     * 但 body 仍带完整 components 明细，正是本用例要断言的内容。若在此处一律 throw，
     * 就拿不到 body、无法做组件级断言（首版即因此假失败：CI 实测 503 + db/redis 均 UP）。
     * 需要校验状态码的调用方自行断言，如 {@link #assertThatLivenessIsUp}。
     */
    private static HttpResponse<String> send(GenericContainer<?> server, String path)
            throws IOException, InterruptedException {
        URI uri = URI.create(String.format("http://%s:%d%s",
                server.getHost(), server.getMappedPort(SERVER_PORT), path));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertThatLivenessIsUp(GenericContainer<?> server) throws Exception {
        HttpResponse<String> resp = send(server, "/actuator/health/liveness");
        String body = resp.body();
        System.out.println("[gf-topology] /actuator/health/liveness HTTP " + resp.statusCode()
                + " = " + truncate(body));
        // liveness 只含应用自身状态，不受 db/redis/keycloak 影响，故必须是 200 + UP
        if (resp.statusCode() != 200 || !body.contains("\"status\":\"UP\"")) {
            throw new AssertionError("liveness 未 UP，HTTP " + resp.statusCode() + " 响应=" + truncate(body)
                    + "；容器日志尾部=" + truncate(logTail(server)));
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 1200 ? s : s.substring(0, 1200) + "...(截断)";
    }

    /**
     * 断言聚合健康响应中指定组件为 UP。
     *
     * <p>用花括号配平提取组件子树，而非全文 {@code contains}——后者会把兄弟组件的
     * {@code "status":"UP"} 误判成本组件通过（例如 db DOWN 但 redis UP 时）。
     */
    private static void assertComponentUp(String healthJson, String component, GenericContainer<?> server) {
        String key = "\"" + component + "\":{";
        int start = healthJson.indexOf(key);
        if (start < 0) {
            throw new AssertionError("健康响应缺少组件 " + component + "（该组件未装配？），响应="
                    + truncate(healthJson) + "；容器日志尾部=" + truncate(logTail(server)));
        }
        int from = start + key.length() - 1;
        int depth = 0;
        int end = -1;
        for (int i = from; i < healthJson.length(); i++) {
            char c = healthJson.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end < 0) {
            throw new AssertionError("健康响应中组件 " + component + " 的子树未闭合，响应=" + truncate(healthJson));
        }
        String subtree = healthJson.substring(start, end);
        if (!subtree.contains("\"status\":\"UP\"")) {
            throw new AssertionError("组件 " + component + " 未 UP，子树=" + truncate(subtree)
                    + "；容器日志尾部=" + truncate(logTail(server)));
        }
        System.out.println("[gf-topology] 健康组件 " + component + " = UP");
    }

    /** 取容器日志末 30 行，供启动失败时定位（否则 CI 上只看到断言消息、看不到真实异常）。 */
    private static String logTail(GenericContainer<?> server) {
        try {
            String logs = server.getLogs();
            String[] lines = logs.split("\\R");
            int from = Math.max(0, lines.length - 30);
            return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
        } catch (RuntimeException ex) {
            return "<取容器日志失败: " + ex.getMessage() + ">";
        }
    }

    /**
     * 定位 `foundation-server` 的**可执行** jar。
     *
     * <p>优先取 {@code -Dfoundation.server.jar}；否则在候选目录下找最新的
     * {@code foundation-server-*-exec.jar}。
     *
     * <p><b>必须匹配 {@code -exec} 后缀</b>：子仓的 repackage 配了 {@code classifier=exec}
     * （否则主 artifact 被换成 fat jar，会使其 failsafe 找不到 {@code @SpringBootApplication}
     * 而全部 IT 失败），故 target 下同时存在两个 jar：
     * <ul>
     *   <li>{@code foundation-server-<v>.jar} —— 普通 jar，**不可** {@code java -jar}；</li>
     *   <li>{@code foundation-server-<v>-exec.jar} —— fat jar，容器内启动用。</li>
     * </ul>
     * 只按「最新修改时间」挑会命中前者而让容器起不来，故此处显式要求 exec 后缀。
     * surefire/failsafe 的工作目录是被测模块（{@code tioka-server}），故子仓在其上一级。
     */
    private static Path findServerJar() {
        String override = System.getProperty("foundation.server.jar");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override);
            return Files.isRegularFile(p) ? p : null;
        }
        String[] candidateDirs = {
                "../tioka-foundation/foundation-server/target",
                "tioka-foundation/foundation-server/target",
        };
        for (String dir : candidateDirs) {
            Path d = Paths.get(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.list(d)) {
                Optional<Path> hit = files
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.startsWith("foundation-server-")
                                    && n.endsWith("-exec.jar");
                        })
                        // 可能有多个版本残留，取最新修改的那个
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
                if (hit.isPresent()) {
                    return hit.get();
                }
            } catch (IOException ignored) {
                // 换下一个候选目录
            }
        }
        return null;
    }
}
