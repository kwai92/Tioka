package com.tioka.server.foundation;

import com.tioka.framework.security.AuthorizationSnapshotSource;
import com.tioka.framework.security.FoundationPermissionEvaluator;
import com.tioka.framework.security.PermissionEvaluator;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.spi.PrincipalLoaderApi;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * foundation 模式的**完整应用上下文**授权链路闸门（F1 债务 / 判据①「消费方先行」的真实运行时验证入口）。
 *
 * <p><b>为什么还需要这个用例</b>：既有覆盖存在明确缺口，且两者都不启动完整应用——
 * <ul>
 *   <li>{@link FoundationAuthorizationChainTest}：手工 {@code new} 出 POJO 链路，验证 HTTP 桩到判定器的语义；</li>
 *   <li>{@link FoundationAuthzConfigurationTest} 与 {@code FoundationModeSwitchTest}：
 *       {@code ApplicationContextRunner} 切片，验证装配与 fail-fast。</li>
 * </ul>
 * 也就是说，此前**没有任何测试在 foundation 模式下启动过完整的 Tioka 应用**：
 * starter-client 自动装配（14 个 Bean）、{@code FoundationModeGuard} 启动期护栏、
 * {@code PermissionAspect} 切面、{@code FoundationPermissionEvaluator} 判定、拒绝审计写入，
 * 这五者从未在同一次真实启动中协同工作过。本用例填补这一层，同时让 P0 接入护栏与
 * P1 版本化缓存获得真实运行时消费方（此前它们只被平台自身单测验证，正是判据①登记的违例）。
 *
 * <p><b>边界（刻意不冒充 F-00 §6.1 的 GfTiokaModeGateIT）</b>：本用例用 {@link MockWebServer}
 * 扮演中台授权快照端点，不起 Keycloak 与真实 foundation-server——中台的入站 JWT 校验是平台自身职责
 * （由其仓库 CI 覆盖），这里要验的是**接入方侧**的装配与判定。因此不需要中台种子数据、也不依赖
 * BAS-20 的用户供给。「G1 全部业务闸门在 foundation 模式下通过」仍以 BAS-20（用户与角色迁出）为前置，
 * 不在本用例范围；本用例覆盖的是其**可独立验证的权限判定子集**。
 *
 * <p><b>命名与分层</b>：{@code *IT} 且**不带** {@code @Tag("gate")}，故由 failsafe 在较快的 backend job
 * 执行（surefire 默认排除 IT，failsafe 默认排除 gate 组）；只需 PG 与 Redis 容器。
 *
 * <p><b>两处防假阳性的刻意配置</b>：
 * <ol>
 *   <li>{@code foundation.client.authz.cache-ttl=0ms} —— P1 版本化缓存默认 TTL 5 分钟，
 *       若沿用会让「中台不可达」用例命中前一用例缓存的快照而误判通过；置 0 后每次判定都真实回源。</li>
 *   <li>{@code foundation.client.retry.max-attempts=1} —— 默认重试 3 次并指数退避，
 *       会让故障场景既慢、又与「真实回源次数」不一致。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("foundation 模式完整上下文：中台授权快照驱动权限判定")
class GfTiokaFoundationModeAuthzIT {

    /** 与 {@code foundation.app-code} 一致；快照路径为 /api/fdn/apps/{appCode}/persons/{personId}/snapshot。 */
    private static final String APP_CODE = "tioka";

    private static final Pattern SNAPSHOT_PATH =
            Pattern.compile("^/api/fdn/apps/" + APP_CODE + "/persons/([^/]+)/snapshot$");

    /** personId → 中台返回的权限项。每个用例只放自己的人，互不污染。 */
    private static final Map<String, List<String>> GRANTS = new ConcurrentHashMap<>();

    /** 桩侧留痕：被查询过快照的 personId，用于坐实「权限确实来自中台回源」而非本地。 */
    private static final List<String> QUERIED_PERSON_IDS =
            Collections.synchronizedList(new java.util.ArrayList<>());

    /** 置真时中台一律 500，用于验证 fail-closed（绝不回落本地权限集）。 */
    private static final AtomicBoolean PLATFORM_DOWN = new AtomicBoolean(false);

    /**
     * 用静态初始化启动，而非 {@code @BeforeAll}：{@code @DynamicPropertySource} 在 ApplicationContext
     * 加载时求值 {@code platform.url(...)}，而 JUnit 的 {@code @BeforeAll} 与该求值的先后**不保证**；
     * 静态字段初始化在类加载期完成，一定早于任何求值，避免 NPE（Spring 官方 Testcontainers 示例同款）。
     */
    private static final MockWebServer platform = newPlatform();

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mockMvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private AuditApi audit;
    /** 与权限判定无关：仅用于「自证前提」——确认该主体在本地权限体系下确实持有目标权限码。 */
    @Autowired private PrincipalLoaderApi principalLoader;

    private static MockWebServer newPlatform() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (PLATFORM_DOWN.get()) {
                    return new MockResponse().setResponseCode(500).setBody("platform down");
                }
                Matcher m = SNAPSHOT_PATH.matcher(path);
                if (!m.matches()) {
                    // 未知路径返回 404，避免把「请求打错地方」伪装成「中台拒绝」
                    return new MockResponse().setResponseCode(404).setBody("no such endpoint");
                }
                String personId = m.group(1);
                QUERIED_PERSON_IDS.add(personId);
                List<String> codes = GRANTS.get(personId);
                if (codes == null) {
                    // 中台无此人授权记录：与真实实现一致地 404，客户端按「快照不可用」处理
                    return new MockResponse().setResponseCode(404).setBody("{\"code\":\"NOT_FOUND\"}");
                }
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(snapshotJson(personId, codes));
            }
        });
        try {
            server.start();
        } catch (IOException ex) {
            throw new IllegalStateException("无法启动中台授权快照桩（MockWebServer）", ex);
        }
        return server;
    }

    @AfterAll
    static void stopPlatformStub() throws IOException {
        platform.shutdown();
    }

    @DynamicPropertySource
    static void foundationModeProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        // 两个 mode 键在生产由同一环境变量派生（application.yml），此处显式给同一值以固定模式
        registry.add("tioka.foundation.mode", () -> "foundation");
        registry.add("foundation.client.mode", () -> "foundation");
        registry.add("foundation.enabled", () -> "true");
        registry.add("foundation.app-code", () -> APP_CODE);
        registry.add("foundation.base-url", () -> platform.url("/").toString().replaceAll("/$", ""));
        // 服务账号凭据留空 → 出站不带 Authorization。中台侧的应用编码校验属平台职责，
        // 桩刻意不校验令牌；本用例只验接入方侧装配与判定。
        registry.add("foundation.client.authz.cache-ttl", () -> "0ms");
        registry.add("foundation.client.retry.max-attempts", () -> "1");
    }

    @BeforeEach
    void resetPlatformState() {
        GRANTS.clear();
        QUERIED_PERSON_IDS.clear();
        PLATFORM_DOWN.set(false);
    }

    @Test
    @DisplayName("foundation 模式装配出中台判定器，而非本地判定器")
    void foundationModeAssemblesPlatformDrivenPermissionEvaluator() {
        // 用标准容器 API 计数（hasSingleBean 是 ApplicationContextAssert 专有，
        // 仅 ApplicationContextRunner 切片可用，全上下文测试须用 getBeansOfType）。
        assertThat(context.getBeansOfType(AuthorizationSnapshotSource.class)).hasSize(1);
        Map<String, PermissionEvaluator> evaluators = context.getBeansOfType(PermissionEvaluator.class);
        assertThat(evaluators).hasSize(1);
        // 关键：判定来源必须是中台快照判定器。若装配成 LocalPermissionEvaluator，
        // 说明「配置写着接入中台、运行时按本地权限跑」的半接线状态没有被守住。
        assertThat(evaluators.values().iterator().next())
                .isInstanceOf(FoundationPermissionEvaluator.class);
    }

    @Test
    @DisplayName("中台授予全部已登记权限码时，读写端点均放行且确实回源中台")
    void platformGrantingAllRegisteredCodesAllowsReadAndWrite() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        // 按权限码映射契约全量授予：这是「G1 闸门在 foundation 模式下通过」可独立验证的子集，
        // 不需要中台侧真实业务数据，也不改 Tioka 任何业务行为。
        GRANTS.put(actor.userId(), Arrays.asList(Permissions.ALL_CONCRETE));

        mockMvc.perform(actor.auth(get("/api/agents")))
                .andExpect(status().isOk());
        mockMvc.perform(actor.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"gf-foundation-mode","workspaceId":"%s"}
                                """.formatted(actor.workspaceId())))
                .andExpect(status().isOk());

        // 坐实「权限来自中台回源」而非本地权限集：桩确实收到过该人的快照请求
        assertThat(QUERIED_PERSON_IDS)
                .as("权限判定必须真实回源中台快照端点")
                .contains(actor.userId());
    }

    @Test
    @DisplayName("中台未授予任何权限码时，读写端点均 403 并留下 DENIED 审计")
    void platformGrantingNothingDeniesWithAudit() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        GRANTS.put(actor.userId(), List.of());

        mockMvc.perform(actor.auth(get("/api/agents")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        List<?> denied = deniedAuditOf(actor);
        assertThat(denied)
                .as("拒绝必须留下审计，否则越权尝试不可追溯（ADR-072）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("主体自带本地权限集也不参与判定：中台没给就拒绝，绝不回落本地")
    void localPermissionsAreNotConsultedInFoundationMode() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");

        // 前提自证而非假设：先在 local 语义下确认该主体确实自带 agent:read（否则本用例
        // 只是重复「中台没给就拒绝」，证不出「不回落本地」这条约束）。
        Principal localView = loadPrincipal(actor);
        assertThat(localView.permissions())
                .as("本用例前提：该主体在本地权限体系下确实持有 agent:read，"
                        + "否则无法证明 foundation 模式忽略了它")
                .contains("agent:read");

        // 中台一个权限都不给：若判定回落本地，上面的 agent:read 会让请求通过
        GRANTS.put(actor.userId(), List.of());

        mockMvc.perform(actor.auth(get("/api/agents")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("中台不可达时一律拒绝（fail-closed），不静默放行")
    void platformUnavailableFailsClosed() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        GRANTS.put(actor.userId(), Arrays.asList(Permissions.ALL_CONCRETE));
        PLATFORM_DOWN.set(true);

        mockMvc.perform(actor.auth(get("/api/agents")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("中台只给读权限时：读放行、写拒绝（判定按码而非按登录态）")
    void partialGrantAllowsReadButDeniesWrite() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        GRANTS.put(actor.userId(), List.of("agent:read"));

        mockMvc.perform(actor.auth(get("/api/agents")))
                .andExpect(status().isOk());
        mockMvc.perform(actor.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"gf-should-be-denied","workspaceId":"%s"}
                                """.formatted(actor.workspaceId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    private List<?> deniedAuditOf(TestFixtures.Actor actor) {
        // 10 参兼容口径：仅按 (workspaceId, actorId, DENIED) 过滤，其余条件传 null
        return audit.query(new AuditApi.AuditQuery(
                actor.workspaceId(), actor.userId(), null, null, null,
                AuditOutcome.DENIED, null, null, null, 50));
    }

    /**
     * 按本地权限体系装载主体（{@code PrincipalLoader} 与运行模式无关，始终读 Tioka 自身库）。
     * 仅用于「自证前提」，绝不参与被测的判定链路——foundation 模式的判定只认中台快照。
     */
    private Principal loadPrincipal(TestFixtures.Actor actor) {
        return principalLoader.load(Long.parseLong(actor.userId()), actor.workspaceId());
    }

    private static String snapshotJson(String personId, List<String> codes) {
        StringBuilder json = new StringBuilder("{\"personId\":\"").append(personId)
                .append("\",\"appCode\":\"").append(APP_CODE)
                .append("\",\"version\":1,\"permissions\":[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(codes.get(i)).append('"');
        }
        return json.append("]}").toString();
    }
}
