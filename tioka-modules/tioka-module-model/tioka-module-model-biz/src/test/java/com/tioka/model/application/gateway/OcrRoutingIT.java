package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.api.capability.OcrRequest;
import com.tioka.model.api.capability.OcrResult;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import com.tioka.model.infrastructure.provider.OcrEngine;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.QuotaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * DOC-02 OcrRouting（ADR-060 §4 / ADR-061）：OCR 经 ModelGateway.ocr（配额/用量/审计同链），
 * CONFIDENTIAL 文档的 OCR 请求随绑定解析按空间作用域路由；数据标签随请求传递。
 *
 * <p>偏差登记：ADR-040 去向许可（DAT-02）尚未建——「只走空间去向许可允许的模型」在本批
 * 降级为「路由按空间绑定解析 + dataLabel/spaceId 随请求传递（M4 PRS-02 接去向过滤）」口径。
 */
class OcrRoutingIT {

    private ModelAdminService admin;
    private ModelEntities entities;
    private List<ModelUsageDO> capturedRows;
    private UsageRecorder usage;
    private DefaultModelGateway gateway;
    private CapturingEngine engine;
    private String bindingId;

    /** 桩引擎：捕获请求，返回确定性行。 */
    static final class CapturingEngine implements OcrEngine {
        final List<OcrRequest> requests = new ArrayList<>();
        final List<String> models = new ArrayList<>();

        @Override
        public OcrResult recognize(String baseUrl, String apiKeyRef, String model,
                                   OcrRequest request) {
            requests.add(request);
            models.add(model);
            return new OcrResult(List.of(new OcrResult.OcrLine("第一条 租金", 0.1, 0.2, 0.8, 0.3, 0.95)),
                    null, model);
        }

        /** 未配置 endpoint 的适配器口径：ocr_unavailable。 */
        static final OcrEngine UNAVAILABLE = (baseUrl, apiKeyRef, model, request) -> {
            throw new BizException(ErrorCode.DEPENDENCY,
                    "ocr_unavailable: OCR endpoint 未配置（tioka.model.ocr.endpoint）");
        };
    }

    @BeforeEach
    void setUp() {
        entities = new ModelEntities(new MinimalModelEntitiesRepository());
        engine = new CapturingEngine();
        admin = new ModelAdminService(new MemorySecretStore(), entities,
                mock(com.tioka.framework.security.PrincipalHolder.class),
                ModelTestSupport.disabledVersioning(entities));
        String providerId = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k")).id();
        String lm = admin.createLogicalModel(
                new com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest("ocr-m", "ocr"));
        bindingId = admin.createModelBinding(new CreateModelBindingRequest(
                lm, providerId, "qwen-vl-ocr", "ocr", null));

        ModelUsageRepository usageRepo = mock(ModelUsageRepository.class);
        capturedRows = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            capturedRows.addAll(inv.getArgument(0));
            return null;
        }).when(usageRepo).insertBatch(org.mockito.ArgumentMatchers.anyList());
        usage = new UsageRecorder(usageRepo);

        gateway = new DefaultModelGateway(
                new ModelBindingResolver(entities, List.of()),
                new ProviderClient(new MemorySecretStore(), mock(EgressClient.class), true, 8, new ObjectMapper()),
                engine,
                entities,
                mock(ApplicationEventPublisher.class),
                usage,
                mock(QuotaGuard.class),
                mock(ModelRateLimiter.class),
                mock(ModelBindingCooldownService.class),
                new ModelBudgetGuard(usageRepo),
                2, 2, Duration.ofMillis(1), Duration.ofSeconds(1),
                emptyRouting());
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.tioka.model.application.routing.RoutingPlanExecutor> emptyRouting() {
        return (org.springframework.beans.factory.ObjectProvider<com.tioka.model.application.routing.RoutingPlanExecutor>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    private ModelCallContext ctx() {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, null, null, null, null, ModelPurpose.OCR);
    }

    @Test
    void confidentialOcrRoutedThroughGatewayWithLabelAndDestination() {
        OcrResult result = gateway.ocr(ctx(), new OcrRequest(bindingId,
                new byte[]{1, 2, 3}, "CONFIDENTIAL", "ws1"));

        assertEquals(bindingId, result.bindingId(), "目的地 = 空间解析出的绑定");
        assertEquals(1, engine.requests.size());
        assertEquals("CONFIDENTIAL", engine.requests.get(0).dataLabel(), "数据标签随请求传递");
        assertEquals("ws1", engine.requests.get(0).spaceId(), "空间归属随请求传递");
        assertEquals("qwen-vl-ocr", engine.models.get(0));
        assertEquals(0.95d, result.lines().get(0).confidence());
        // 用量记账 SUCCESS（与 chat 同链：配额/用量/审计）
        usage.flush();
        assertEquals(1, capturedRows.size());
        assertEquals("SUCCESS", capturedRows.get(0).getStatus());
        assertEquals(bindingId, capturedRows.get(0).getBindingId());
    }

    @Test
    void ocrUnavailableSurfacedAsDependencyFailure() {
        DefaultModelGateway unavailableGateway = new DefaultModelGateway(
                new ModelBindingResolver(entities, List.of()),
                new ProviderClient(new MemorySecretStore(), mock(EgressClient.class), true, 8, new ObjectMapper()),
                CapturingEngine.UNAVAILABLE,
                entities,
                mock(ApplicationEventPublisher.class),
                usage,
                mock(QuotaGuard.class),
                mock(ModelRateLimiter.class),
                mock(ModelBindingCooldownService.class),
                new ModelBudgetGuard(mock(ModelUsageRepository.class)),
                2, 2, Duration.ofMillis(1), Duration.ofSeconds(1),
                emptyRouting());

        BizException e = assertThrows(BizException.class, () -> unavailableGateway.ocr(ctx(),
                new OcrRequest(bindingId, new byte[]{1}, "CONFIDENTIAL", "ws1")));
        assertEquals(ErrorCode.DEPENDENCY, e.getErrorCode());
        assertTrue(e.getMessage().contains("ocr_unavailable"), () -> e.getMessage());
    }

    private static final class MemorySecretStore implements com.tioka.system.api.spi.SecretStore {
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

    /** OCR forced 路径所需的最小仓库实现（provider + binding）。 */
    static final class MinimalModelEntitiesRepository implements ModelEntitiesRepository {
        private final Map<String, com.tioka.model.infrastructure.persistence.ModelProviderDO> providers = new ConcurrentHashMap<>();
        private final Map<String, com.tioka.model.infrastructure.persistence.ModelBindingDO> bindings = new ConcurrentHashMap<>();

        @Override
        public void putProvider(com.tioka.model.infrastructure.persistence.ModelProviderDO row) {
            providers.put(row.getId(), row);
        }

        @Override
        public Optional<com.tioka.model.infrastructure.persistence.ModelProviderDO> getProvider(String id) {
            return Optional.ofNullable(providers.get(id));
        }

        @Override
        public List<com.tioka.model.infrastructure.persistence.ModelProviderDO> providers() {
            return List.copyOf(providers.values());
        }

        @Override
        public void putAlias(com.tioka.model.infrastructure.persistence.ModelAliasDO row) {
            // unused
        }

        @Override
        public boolean hasAlias(String id) {
            return true;
        }

        @Override
        public Optional<com.tioka.model.infrastructure.persistence.ModelAliasDO> getAlias(String id) {
            return Optional.empty();
        }

        @Override
        public List<com.tioka.model.infrastructure.persistence.ModelAliasDO> aliases() {
            return List.of();
        }

        @Override
        public Optional<com.tioka.model.infrastructure.persistence.ModelAliasDO> findAliasByName(String name) {
            return Optional.empty();
        }

        @Override
        public void putBinding(com.tioka.model.infrastructure.persistence.ModelBindingDO row) {
            bindings.put(row.getId(), row);
        }

        @Override
        public Optional<com.tioka.model.infrastructure.persistence.ModelBindingDO> getModelBinding(String id) {
            return Optional.ofNullable(bindings.get(id));
        }

        @Override
        public boolean hasBinding(String id) {
            return bindings.containsKey(id);
        }

        @Override
        public List<com.tioka.model.infrastructure.persistence.ModelBindingDO> bindings() {
            return List.copyOf(bindings.values());
        }

        @Override
        public void putPlan(com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO row) {
            // unused
        }

        @Override
        public Optional<com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO> getPlan(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO> lockPlanForShare(String id) {
            return Optional.empty();
        }

        @Override
        public List<com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO> plans() {
            return List.of();
        }

        @Override
        public Optional<String> getFreeze(String threadId, String turnId) {
            return Optional.empty();
        }

        @Override
        public String insertFreezeIfAbsent(String threadId, String turnId, String bindingId, String reason) {
            return bindingId;
        }

        @Override
        public void putFreeze(String threadId, String turnId, String bindingId) {
            // unused
        }

        @Override
        public void recordFailover(String threadId, String turnId, String bindingId, String reason) {
            // unused
        }

        @Override
        public List<com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO> listFreezes(String threadId) {
            return List.of();
        }
    }
}
