package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelGateway.EmbeddingResult;
import com.tioka.model.api.gateway.ModelGateway.RerankRequest;
import com.tioka.model.api.gateway.ModelGateway.RerankResult;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelAliasDO;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.SecretStore;
import com.tioka.system.api.spi.QuotaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * MH-04（GAP-L5-4）：rerank 全链离线单测——选模（forced）→ 预算预检 → 限流 → mock
 * provider → 记账。同时回归 embed 链路共用错误记账改造后行为不变。
 */
class DefaultModelGatewayRerankTest {

    private ModelAdminService admin;
    private ModelEntities entities;
    private ModelUsageRepository usageRepo;
    private java.util.List<ModelUsageDO> capturedRows;
    private UsageRecorder usage;
    private DefaultModelGateway gateway;
    private String bindingId;
    private String providerId;

    @BeforeEach
    void setUp() {
        entities = new ModelEntities(new MinimalModelEntitiesRepository());
        SecretStore store = new MemorySecretStore();
        admin = new ModelAdminService(store, entities,
                mock(com.tioka.framework.security.PrincipalHolder.class),
                ModelTestSupport.disabledVersioning(entities));
        providerId = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k")).id();
        String lm = admin.createLogicalModel(
                new com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest("rerank-m", "rerank"));
        bindingId = admin.createModelBinding(new CreateModelBindingRequest(
                lm, providerId, "rerank-model", "rerank", null));

        usageRepo = mock(ModelUsageRepository.class);
        // flush() 内部会复用并 clear batch 列表，ArgumentCaptor 捕获的是引用——
        // 这里用 doAnswer 在调用瞬间复制行，避免捕获到被清空后的列表
        capturedRows = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            capturedRows.addAll(inv.getArgument(0));
            return null;
        }).when(usageRepo).insertBatch(org.mockito.ArgumentMatchers.anyList());
        usage = new UsageRecorder(usageRepo);
        ModelBudgetGuard budget = new ModelBudgetGuard(usageRepo);
        gateway = new DefaultModelGateway(
                new ModelBindingResolver(entities, List.of()),
                new ProviderClient(store, mock(EgressClient.class), true, 8, new ObjectMapper()),
                (com.tioka.model.infrastructure.provider.OcrEngine) (base, key, model, req) -> new com.tioka.model.api.capability.OcrResult(java.util.List.of(), null, "mock"),
                entities,
                mock(ApplicationEventPublisher.class),
                usage,
                mock(QuotaGuard.class),
                mock(ModelRateLimiter.class),
                mock(ModelBindingCooldownService.class),
                budget,
                2, 2, Duration.ofMillis(1), Duration.ofSeconds(1),
                emptyRouting());
    }

    /** MH-06：未装配路由编排（ObjectProvider 为空）时的网关行为回归。 */
    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.tioka.model.application.routing.RoutingPlanExecutor> emptyRouting() {
        return (org.springframework.beans.factory.ObjectProvider<com.tioka.model.application.routing.RoutingPlanExecutor>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    private ModelCallContext ctx() {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, null, null, null, null, ModelPurpose.RERANK);
    }

    @Test
    void rerankOrdersAndTruncatesToTopN() {
        RerankResult r = gateway.rerank(ctx(), new RerankRequest(bindingId, "模型预算配置",
                List.of("散步好去处", "模型预算配置说明", "天气预报"), 2));

        assertEquals(bindingId, r.bindingId());
        assertEquals(2, r.results().size(), "topN=2 截断");
        assertEquals(1, r.results().get(0).index(), "相关文档排首位");
        assertTrue(r.results().get(0).score() >= r.results().get(1).score(), "分数降序");
    }

    @Test
    void rerankRecordsUsageRow() {
        gateway.rerank(ctx(), new RerankRequest(bindingId, "查询", List.of("文档一"), 1));
        usage.flush();
        assertEquals(1, capturedRows.size());
        // modelRef = logicalModelId@modelName，校验尾部绑定模型名
        assertEquals("rerank-model", capturedRows.get(0).getModelRef()
                .substring(capturedRows.get(0).getModelRef().indexOf('@') + 1));
        assertEquals("SUCCESS", capturedRows.get(0).getStatus());
        assertEquals(providerId, capturedRows.get(0).getProviderId());
    }

    @Test
    void rerankRejectsBlankQueryOrEmptyDocuments() {
        assertThrows(com.tioka.framework.error.BizException.class,
                () -> gateway.rerank(ctx(), new RerankRequest(bindingId, " ", List.of("d"), 1)));
        assertThrows(com.tioka.framework.error.BizException.class,
                () -> gateway.rerank(ctx(), new RerankRequest(bindingId, "q", List.of(), 1)));
    }

    @Test
    void embedStillWorksAfterAccountingRefactor() {
        // accountEmbedError → accountError 合并后的回归：embed 正常链路不受影响
        EmbeddingResult e = gateway.embed(ctx(), new ModelGateway.EmbeddingRequest(null, List.of("文本")));
        assertEquals(1, e.vectors().size());
        usage.flush();
        org.mockito.Mockito.verify(usageRepo, org.mockito.Mockito.atLeastOnce()).insertBatch(org.mockito.ArgumentMatchers.anyList());
        assertEquals("SUCCESS", lastUsageStatus());
    }

    private String lastUsageStatus() {
        return capturedRows.get(capturedRows.size() - 1).getStatus();
    }

    /** rerank forced 路径所需的最小仓库实现（provider + binding）。 */
    static final class MinimalModelEntitiesRepository implements ModelEntitiesRepository {
        private final Map<String, ModelProviderDO> providers = new ConcurrentHashMap<>();
        private final Map<String, ModelBindingDO> bindings = new ConcurrentHashMap<>();

        @Override
        public void putProvider(ModelProviderDO row) { providers.put(row.getId(), row); }

        @Override
        public Optional<ModelProviderDO> getProvider(String id) { return Optional.ofNullable(providers.get(id)); }

        @Override
        public List<ModelProviderDO> providers() { return List.copyOf(providers.values()); }

        @Override
        public void putAlias(ModelAliasDO row) { /* unused */ }

        @Override
        public boolean hasAlias(String id) { return true; }

        @Override
        public Optional<ModelAliasDO> getAlias(String id) { return Optional.empty(); }

        @Override
        public List<ModelAliasDO> aliases() { return List.of(); }

        @Override
        public Optional<ModelAliasDO> findAliasByName(String name) { return Optional.empty(); }

        @Override
        public void putBinding(ModelBindingDO row) { bindings.put(row.getId(), row); }

        @Override
        public Optional<ModelBindingDO> getModelBinding(String id) { return Optional.ofNullable(bindings.get(id)); }

        @Override
        public boolean hasBinding(String id) { return bindings.containsKey(id); }

        @Override
        public List<ModelBindingDO> bindings() { return List.copyOf(bindings.values()); }

        @Override
        public void putPlan(ModelRoutingPlanDO row) { /* unused */ }

        @Override
        public Optional<ModelRoutingPlanDO> getPlan(String id) { return Optional.empty(); }

        @Override
        public Optional<ModelRoutingPlanDO> lockPlanForShare(String id) { return Optional.empty(); }

        @Override
        public List<ModelRoutingPlanDO> plans() { return List.of(); }

        @Override
        public Optional<String> getFreeze(String threadId, String turnId) { return Optional.empty(); }

        @Override
        public String insertFreezeIfAbsent(String threadId, String turnId, String bindingId, String reason) {
            return bindingId;
        }

        @Override
        public void putFreeze(String threadId, String turnId, String bindingId) { /* unused */ }

        @Override
        public void recordFailover(String threadId, String turnId, String bindingId, String reason) { /* unused */ }

        @Override
        public List<ModelTurnFreezeDO> listFreezes(String threadId) { return List.of(); }
    }

    static final class MemorySecretStore implements SecretStore {
        private final Map<String, String> map = new ConcurrentHashMap<>();

        @Override
        public String put(String namespace, String key, String plaintext) {
            String id = namespace + "::" + key;
            map.put(id, plaintext);
            return id;
        }

        @Override
        public String get(String namespace, String key) {
            return map.get(namespace + "::" + key);
        }

        @Override
        public void delete(String namespace, String key) {
            map.remove(namespace + "::" + key);
        }
    }
}
