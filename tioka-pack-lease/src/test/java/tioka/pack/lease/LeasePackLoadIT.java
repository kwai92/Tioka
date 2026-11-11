package tioka.pack.lease;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.fold.FoldLock;
import com.tioka.ontology.application.fold.FoldScheduler;
import com.tioka.ontology.application.pack.CelFunction;
import com.tioka.ontology.application.pack.DomainPack;
import com.tioka.ontology.application.pack.DomainPackLoadResult;
import com.tioka.ontology.application.pack.DomainPackLoader;
import com.tioka.ontology.application.pack.FoldingEngine;
import com.tioka.ontology.application.pack.ModelValidator;
import com.tioka.ontology.application.pack.PackLoadOutcome;
import com.tioka.ontology.application.pack.PackPluginGateway;
import com.tioka.ontology.application.pack.PackSignatureVerifier;
import com.tioka.ontology.application.pack.ProjectionHook;
import com.tioka.ontology.application.pack.PureFunctionExecutor;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.ontology.api.mq.OntologyMqTopics;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntStateRepository;
import com.tioka.ontology.infrastructure.mapper.OntInstanceVersionMapper;
import com.tioka.ontology.infrastructure.mapper.OntStateMapper;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntStateDO;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-9/LSE-01〜04 加载验证 IT：Maven 打包产物（target/classes + 模块根资源）打成真实 jar，
 * 经 {@link DomainPackLoader#load(Path)} 完整链路加载（manifest → 权限 → 独立类加载器 →
 * schema 注册 → saveDraft 登记挂接），并联动 FoldScheduler 验证 lease 折叠落 ont_state。
 */
class LeasePackLoadIT {

    static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path temp;

    // ── Part A：真实 jar 端到端加载 ──────────────────────────

    /** 模块根（Maven 测试 working dir = 模块目录）。 */
    private static Path moduleRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    /** 把 target/classes 与模块根静态资源（tioka-plugin.json / definitions / schemas / prompts / seed / config）打成 jar。 */
    private Path buildPackJar() throws Exception {
        Path jar = temp.resolve("tioka-pack-lease-1.0.0.jar");
        Path classes = moduleRoot().resolve("target/classes");
        java.util.Set<String> written = new java.util.HashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    putEntry(out, classes.relativize(p).toString().replace('\\', '/'),
                            Files.readAllBytes(p), written);
                }
            }
            for (String res : List.of("tioka-plugin.json", "plugin.yaml")) {
                Path p = moduleRoot().resolve(res);
                if (Files.exists(p)) {
                    putEntry(out, res, Files.readAllBytes(p), written);
                }
            }
            for (String dir : List.of("definitions", "schemas", "prompts", "seed", "config")) {
                Path root = moduleRoot().resolve(dir);
                if (!Files.exists(root)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path p : walk.filter(Files::isRegularFile).toList()) {
                        putEntry(out, moduleRoot().relativize(p).toString().replace('\\', '/'),
                                Files.readAllBytes(p), written);
                    }
                }
            }
        }
        return jar;
    }

    private static void putEntry(JarOutputStream out, String name, byte[] bytes,
                                 java.util.Set<String> written) throws Exception {
        if (!written.add(name)) {
            return; // target/classes 已含模块根资源，去重
        }
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    @Test
    void packJar端到端加载与全量挂接() throws Exception {
        Path jar = buildPackJar();
        DefinitionService definitions = new DefinitionService(M, new SchemaRegistry(M));
        DomainPackLoader loader = new DomainPackLoader(M, allowGateway(), definitions,
                new PureFunctionExecutor(30), new PackSignatureVerifier(List.of(), false),
                new SchemaRegistry(M));

        DomainPackLoadResult result = loader.load(jar);

        assertEquals(DomainPackLoadResult.Status.ENABLED, result.status(),
                () -> "load failed: " + result.error());
        assertEquals("lease", result.packKey());
        assertEquals("1.0.0", result.version());

        // 定义草稿登记（register → saveDraft）
        assertTrue(definitions.getDraft(LeaseDomainPack.DEF_KEY).isPresent(),
                "saveDraft 应登记 lease.contract 定义");

        DomainPack pack = loader.get(LeaseDomainPack.PACK_KEY, LeaseDomainPack.VERSION).orElseThrow();

        // schemas 三件注册（manifest.schemas → SchemaRegistry）
        SchemaRegistry schemas = new SchemaRegistry(M);
        // （schemaRegistry 断言见 schemaRegistered 用例；此处校验包内挂接）
        List<ModelValidator> validators = pack.validators();
        assertEquals(16, validators.size(), "V01〜V16 十六条校验器");
        assertTrue(loader.validator(LeaseDomainPack.PACK_KEY, LeaseDomainPack.VERSION,
                "lease.V01.zero-not-free").isPresent());
        Optional<FoldingEngine> folding = loader.folding(LeaseDomainPack.PACK_KEY, LeaseDomainPack.VERSION);
        assertTrue(folding.isPresent(), "折叠引擎挂接");
        List<CelFunction> cel = loader.packFunctions().stream()
                .filter(f -> f.name().startsWith("freeMonths") || f.name().startsWith("escalationRate")
                        || f.name().startsWith("depositMonths") || f.name().startsWith("cureDays")
                        || f.name().startsWith("penaltyCap") || f.name().startsWith("overdueDaysToTerminate")
                        || f.name().startsWith("overdueTimesToTerminate") || f.name().startsWith("hasStatement"))
                .toList();
        assertEquals(8, cel.size(), "八个领域包 CEL 函数");
        List<ProjectionHook> hooks = loader.projections(LeaseDomainPack.PACK_KEY, LeaseDomainPack.VERSION);
        assertTrue(hooks.isEmpty(), "LSE-03 裁决：projections 暂空");
        assertNotNull(pack.definition());
        assertEquals("lease.contract", pack.definition().defKey());
    }

    @Test
    void schema三件经manifest注册() throws Exception {
        Path jar = buildPackJar();
        SchemaRegistry schemas = new SchemaRegistry(M);
        DefinitionService definitions = new DefinitionService(M, schemas);
        DomainPackLoader loader = new DomainPackLoader(M, allowGateway(), definitions,
                new PureFunctionExecutor(30), new PackSignatureVerifier(List.of(), false), schemas);
        DomainPackLoadResult result = loader.load(jar);
        assertEquals(DomainPackLoadResult.Status.ENABLED, result.status(), () -> result.error());
        assertTrue(schemas.get("lease.finding@1").isPresent(), "lease.finding@1 注册");
        assertTrue(schemas.get("lease.findingList@1").isPresent(), "lease.findingList@1 注册");
        assertTrue(schemas.get("lease.contract.inventory@1").isPresent(), "lease.contract.inventory@1 注册");
    }

    private static PackPluginGateway allowGateway() {
        return new PackPluginGateway() {
            @Override
            public PackLoadOutcome enable(Path jarPath) {
                return PackLoadOutcome.enabled(jarPath.getFileName().toString(), "local");
            }

            @Override
            public Optional<Object> getEntry(String pluginKey, String version) {
                return Optional.empty();
            }
        };
    }

    // ── Part B：FoldScheduler 联动（PG 容器，做法同 FoldSchedulerIT）──────────

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_lease_pack")
            .withUsername("tioka")
            .withPassword("tioka");

    static OntInstanceVersionRepository versions;
    static OntStateRepository states;

    @BeforeAll
    static void setUpScheduler() throws Exception {
        PG.start();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
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
        }
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/Ont*Mapper.xml"));
        bean.setConfiguration(new MybatisConfiguration());
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().addMapper(OntInstanceVersionMapper.class);
        factory.getConfiguration().addMapper(OntStateMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        versions = new OntInstanceVersionRepository(template.getMapper(OntInstanceVersionMapper.class));
        states = new OntStateRepository(template.getMapper(OntStateMapper.class));
    }

    @AfterAll
    static void tearDownScheduler() {
        PG.stop();
    }

    @Test
    void foldScheduler联动lease折叠落ont_state() throws Exception {
        Path jar = buildPackJar();
        SchemaRegistry schemas = new SchemaRegistry(M);
        DefinitionService definitions = new DefinitionService(M, schemas);
        DomainPackLoader loader = new DomainPackLoader(M, allowGateway(), definitions,
                new PureFunctionExecutor(30), new PackSignatureVerifier(List.of(), false), schemas);
        assertEquals(DomainPackLoadResult.Status.ENABLED, loader.load(jar).status(), () -> "load failed");

        // 种子：一份主合同实例（CONFIRMED，defKey=lease.contract）
        String storeKey = "lease:OA";
        String instanceKey = storeKey + ":HT-001";
        String modelJson = """
                {"instanceKey":"%s","contractNo":"HT-001","kind":"主合同",
                 "termStart":"2026-01-01","termEnd":"2028-12-31",
                 "subjects":[{"code":"S1"}],
                 "feeItems":[{"feeKey":"RENT@S1","baseAmount":10000,"effectiveDate":"2026-01-01"}]}
                """.formatted(instanceKey);
        OntInstanceVersionDO row = new OntInstanceVersionDO();
        row.setInstanceKey(instanceKey);
        row.setVersion(1);
        row.setStatus("CONFIRMED");
        row.setDefKey(LeaseDomainPack.DEF_KEY);
        row.setDefVersion(1);
        row.setStoreKey(storeKey);
        row.setModelJson(modelJson);
        row.setContentHash("0".repeat(64));
        row.setConfirmedBy("biz-user-1");
        row.setConfirmedAt(Instant.now());
        row.setAclPrincipals(List.of("store:" + storeKey));
        row.setDataLabel("CONFIDENTIAL");
        versions.insert(row);

        EventPublisher publisher = event -> {
        };
        FoldScheduler scheduler = new PublicScheduler(M, versions, states, new NoopLock(),
                new FixedProvider<>(loader), new FixedProvider<>(definitions),
                new PureFunctionExecutor(30), publisher);

        Map<String, Object> payload = new HashMap<>();
        payload.put("instanceId", instanceKey);
        payload.put("versionId", "1");
        payload.put("contentHash", "0".repeat(64));
        payload.put("modelKey", LeaseDomainPack.DEF_KEY);
        ((PublicScheduler) scheduler).handlePublic(envelope(instanceKey, 1), payload);

        OntStateDO state = states.find(storeKey).orElseThrow();
        com.fasterxml.jackson.databind.JsonNode tree = M.readTree(state.getStateJson());
        assertEquals("lease", state.getPackKey());
        assertEquals(1, tree.get("contractCount").asInt());
        assertEquals(10000.0, tree.get("segments").get(0).get("amount").asDouble(), 1e-9);
        assertEquals("HT-001", tree.get("segments").get(0).get("contractNo").asText());
    }

    private static EventEnvelope envelope(String instanceKey, int version) {
        return new EventEnvelope("evt-" + instanceKey + "-" + version,
                OntologyMqTopics.INSTANCE_CONFIRMED, 1, null, null, "ontology-biz",
                null, null, null, null, "instance:" + instanceKey, null, 0, null, null);
    }

    /** 简单 ObjectProvider（FoldSchedulerIT 同款桩）。 */
    static final class FixedProvider<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }

    /** 测试用：跨包公开 handle（FoldScheduler.handle 为 protected）。 */
    static final class PublicScheduler extends FoldScheduler {
        PublicScheduler(ObjectMapper mapper, OntInstanceVersionRepository versions,
                        OntStateRepository states, FoldLock lock,
                        org.springframework.beans.factory.ObjectProvider<DomainPackLoader> packLoader,
                        org.springframework.beans.factory.ObjectProvider<DefinitionService> definitions,
                        PureFunctionExecutor executor, EventPublisher publisher) {
            super(mapper, versions, states, lock, packLoader, definitions, executor, publisher);
        }

        void handlePublic(EventEnvelope envelope, Map<String, Object> payload) {
            handle(envelope, payload);
        }
    }

    static final class NoopLock implements FoldLock {
        @Override
        public Optional<Handle> tryAcquire(String storeKey) {
            return Optional.of(() -> {
            });
        }
    }
}
