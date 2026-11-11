package com.tioka.ontology.application.fold;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.mq.OntologyMqTopics;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.pack.DomainPack;
import com.tioka.ontology.application.pack.DomainPackLoader;
import com.tioka.ontology.application.pack.FoldingEngine;
import com.tioka.ontology.application.pack.ModelValidator;
import com.tioka.ontology.application.pack.PackLoadOutcome;
import com.tioka.ontology.application.pack.PackPluginGateway;
import com.tioka.ontology.application.pack.PackSignatureVerifier;
import com.tioka.ontology.application.pack.ProjectionHook;
import com.tioka.ontology.application.pack.PromptBundle;
import com.tioka.ontology.application.pack.PureFunctionExecutor;
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
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 折叠调度 PG 集成测试（ONT-07，M3-03 §12；V172/V173 直建 DDL，独立容器隔离）：
 * <ul>
 *   <li>orderIndependent：同一组确认事件按 3 种顺序投递，最终 state_json 完全相同
 *       （input_hash 去重生效，fold 只执行一次 / 门店）；</li>
 *   <li>同输入集合重复投递 → 不重复折叠；</li>
 *   <li>无 FoldingEngine → ACK 且不写 ont_state（降级口径）；</li>
 *   <li>投影钩子全部成功 → written_back = true，发 state_folded。</li>
 * </ul>
 */
class FoldSchedulerIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tioka_ont_fold")
            .withUsername("tioka")
            .withPassword("tioka");

    static final ObjectMapper M = new ObjectMapper();

    static OntInstanceVersionRepository versions;
    static OntStateRepository states;
    static EventPublisher publisher;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        DataSource ds = dataSource();
        migrate(ds);
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
        publisher = mock(EventPublisher.class);
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

    /** 测试用领域包：fold 纯函数（对链排序后计数求和，与到达顺序无关）+ 计数投影钩子。 */
    static final class TestDomainPack implements DomainPack {

        final AtomicInteger foldCalls = new AtomicInteger();
        final AtomicInteger projections = new AtomicInteger();

        final FoldingEngine engine = (storeKey, confirmedChain) -> {
            foldCalls.incrementAndGet();
            // 纯函数：按 instanceKey 排序聚合（与链内顺序无关）
            List<Map<String, Object>> sorted = confirmedChain.stream()
                    .sorted((a, b) -> String.valueOf(a.get("instanceKey"))
                            .compareTo(String.valueOf(b.get("instanceKey"))))
                    .toList();
            long total = 0;
            for (Map<String, Object> model : sorted) {
                total += numberOf(model.get("baseAmount"));
            }
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("contractCount", sorted.size());
            state.put("totalBaseAmount", total);
            state.put("reviewItems", List.of(Map.of("item", "RENT@S1", "reason", "R-3")));
            return state;
        };

        final ProjectionHook hook = new ProjectionHook() {
            @Override
            public String hookId() {
                return "test.writeback";
            }

            @Override
            public void project(Map<String, Object> confirmedModel) {
                projections.incrementAndGet();
            }
        };

        static long numberOf(Object v) {
            return v instanceof Number n ? n.longValue() : 0L;
        }

        @Override
        public String packKey() {
            return "test.lease";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public ModelDefinition definition() {
            return new ModelDefinition(1, "test.contract", "lease", null,
                    List.of(), List.of(),
                    List.of(new ModelDefinition.Topic("rent", List.of(), List.of())),
                    Map.of(), null, List.of(), List.of(), List.of());
        }

        @Override
        public PromptBundle prompts() {
            return PromptBundle.empty();
        }

        @Override
        public List<ModelValidator> validators() {
            return List.of();
        }

        @Override
        public Optional<FoldingEngine> folding() {
            return Optional.of(engine);
        }

        @Override
        public List<ProjectionHook> projections() {
            return List.of(hook);
        }

        @Override
        public List<com.tioka.ontology.application.pack.CelFunction> celFunctions() {
            return List.of();
        }
    }

    /** 简单 ObjectProvider（ConfirmIngestorIT 同款桩）。 */
    static final class FixedProvider<T> implements ObjectProvider<T> {
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

    static final class EmptyProvider<T> implements ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new IllegalStateException("no bean");
        }

        @Override
        public T getObject(Object... args) {
            throw new IllegalStateException("no bean");
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }
    }

    static final class NoopLock implements FoldLock {
        @Override
        public Optional<Handle> tryAcquire(String storeKey) {
            return Optional.of(() -> {
            });
        }
    }

    private static DomainPackLoader loaderWith(TestDomainPack pack, DefinitionService definitions) {
        DomainPackLoader loader = new DomainPackLoader(M, new PackPluginGateway() {
            @Override
            public PackLoadOutcome enable(Path jarPath) {
                return PackLoadOutcome.enabled(jarPath.getFileName().toString(), "local");
            }

            @Override
            public Optional<Object> getEntry(String pluginKey, String version) {
                return Optional.empty();
            }
        }, definitions, new PureFunctionExecutor(30),
                new PackSignatureVerifier(List.of(), false), null);
        // 进程内登记（写定义草稿进同一 DefinitionService，folding/projections 挂接）
        loader.register(pack);
        return loader;
    }

    private static DefinitionService definitions() {
        return new DefinitionService(M, null);
    }

    private static void seedConfirmed(String instanceKey, String storeKey, int version, long baseAmount) {
        OntInstanceVersionDO row = new OntInstanceVersionDO();
        row.setInstanceKey(instanceKey);
        row.setVersion(version);
        row.setStatus("CONFIRMED");
        row.setDefKey("test.contract");
        row.setDefVersion(1);
        row.setStoreKey(storeKey);
        row.setModelJson("{\"instanceKey\":\"" + instanceKey + "\",\"baseAmount\":" + baseAmount + "}");
        row.setContentHash("0".repeat(64));
        row.setConfirmedBy("biz-user-1");
        row.setConfirmedAt(Instant.now());
        row.setAclPrincipals(List.of("store:" + storeKey));
        row.setDataLabel("CONFIDENTIAL");
        versions.insert(row);
    }

    private static EventEnvelope envelope(String instanceKey, int version) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("instanceId", instanceKey);
        payload.put("versionId", String.valueOf(version));
        payload.put("contentHash", "0".repeat(64));
        payload.put("modelKey", "test.contract");
        return new EventEnvelope("evt-" + instanceKey + "-" + version,
                OntologyMqTopics.INSTANCE_CONFIRMED, 1, null, null, "ontology-biz",
                null, null, null, null, "instance:" + instanceKey, null, 0, null, payload);
    }

    private static FoldScheduler scheduler(DomainPackLoader loader, DefinitionService definitions) {
        return new FoldScheduler(M, versions, states, new NoopLock(),
                loader == null ? new EmptyProvider<>() : new FixedProvider<>(loader),
                new FixedProvider<>(definitions), new PureFunctionExecutor(30), publisher);
    }

    /** 一个场景：3 份合同（同门店）全部确认，事件按 perm 给定顺序投递；返回 state_json。 */
    private String foldInOrder(String storeKey, int[] perm) {
        List<String> instances = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String instanceKey = storeKey + ":HT-00" + i;
            instances.add(instanceKey);
            seedConfirmed(instanceKey, storeKey, 1, 1000L * i);
        }
        DefinitionService definitions = definitions();
        FoldScheduler scheduler = scheduler(loaderWith(new TestDomainPack(), definitions), definitions);
        for (int idx : perm) {
            scheduler.handle(envelope(instances.get(idx - 1), 1), envelopePayload(instances.get(idx - 1), 1));
        }
        return states.find(storeKey).orElseThrow().getStateJson();
    }

    private static Map<String, Object> envelopePayload(String instanceKey, int version) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("instanceId", instanceKey);
        payload.put("versionId", String.valueOf(version));
        payload.put("contentHash", "0".repeat(64));
        payload.put("modelKey", "test.contract");
        return payload;
    }

    @Test
    void orderIndependent() throws Exception {
        org.mockito.Mockito.reset(publisher);
        String s1 = foldInOrder("lease:OA", new int[]{1, 2, 3});
        String s2 = foldInOrder("lease:OB", new int[]{3, 1, 2});
        String s3 = foldInOrder("lease:OC", new int[]{2, 3, 1});
        // 折叠为纯函数 + input_hash 去重：3 种顺序最终 state_json 完全相同
        assertEquals(s1, s2, "顺序 B 与 A 的 state_json 应完全相同");
        assertEquals(s1, s3, "顺序 C 与 A 的 state_json 应完全相同");
        // jsonb 文本规范化（冒号后带空格），按解析后的树断言（与存储格式无关）
        com.fasterxml.jackson.databind.JsonNode t1 = M.readTree(s1);
        assertEquals(3, t1.get("contractCount").asInt());
        assertEquals(6000, t1.get("totalBaseAmount").asLong());

        // 投影写回：钩子成功 → written_back = true
        OntStateDO state = states.find("lease:OA").orElseThrow();
        assertTrue(state.getWrittenBack(), "投影钩子全部成功后 written_back 应为 true");

        // state_folded 事件（instanceId=触发实例键，stateVersion=inputHash，补 storeKey/inputHash/writtenBack）
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publish(captor.capture());
        DomainEvent folded = captor.getAllValues().stream()
                .filter(e -> OntologyMqTopics.STATE_FOLDED.equals(e.event()))
                .findFirst().orElseThrow();
        assertNotNull(folded.payload().get("instanceId"));
        assertEquals(folded.payload().get("inputHash"), folded.payload().get("stateVersion"));
        assertEquals("lease:OA", folded.payload().get("storeKey"));
        assertEquals(Boolean.TRUE, folded.payload().get("writtenBack"));
    }

    @Test
    void duplicateDeliveryDoesNotRefold() {
        org.mockito.Mockito.reset(publisher);
        TestDomainPack pack = new TestDomainPack();
        DefinitionService definitions = definitions();
        FoldScheduler scheduler = scheduler(loaderWith(pack, definitions), definitions);
        seedConfirmed("lease:OD:HT-001", "lease:OD", 1, 500);

        Map<String, Object> payload = envelopePayload("lease:OD:HT-001", 1);
        scheduler.handle(envelope("lease:OD:HT-001", 1), payload);
        int afterFirst = pack.foldCalls.get();
        assertEquals(1, afterFirst);

        // 同输入集合重复投递 → input_hash 相同 → 不重复折叠
        scheduler.handle(envelope("lease:OD:HT-001", 1), payload);
        assertEquals(afterFirst, pack.foldCalls.get(), "input_hash 去重后 fold 不应再次执行");
    }

    @Test
    void missingEngineDegraded() {
        org.mockito.Mockito.reset(publisher);
        // 无 DomainPackLoader → 无 FoldingEngine：ACK 且不写 ont_state、不发事件
        seedConfirmed("lease:OE:HT-001", "lease:OE", 1, 800);
        FoldScheduler scheduler = scheduler(null, definitions());
        scheduler.handle(envelope("lease:OE:HT-001", 1), envelopePayload("lease:OE:HT-001", 1));
        assertTrue(states.find("lease:OE").isEmpty(), "降级口径：不写 ont_state");
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, never()).publish(captor.capture());
    }

    @Test
    void unknownInstanceAbsorbed() {
        org.mockito.Mockito.reset(publisher);
        // 触发实例查不到（确认事件早于投影到达）→ 记日志正常 ACK，不抛错
        FoldScheduler scheduler = scheduler(loaderWith(new TestDomainPack(), definitions()), definitions());
        scheduler.handle(envelope("lease:OF:HT-404", 1), envelopePayload("lease:OF:HT-404", 1));
        assertTrue(states.find("lease:OF").isEmpty());
    }
}
