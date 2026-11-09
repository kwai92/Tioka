package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.ModelTestSupport.InMemoryModelEntitiesRepository;
import com.tioka.model.ModelTestSupport.MemorySecretStore;
import com.tioka.model.api.dto.ProviderDtos.CreateAliasRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway.RerankRequest;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.QuotaGuard;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;

/**
 * MH-08（ADR-041 §2 / V222）：调用记录带当前生效版本号——经 sys.* 系统别名解析时，
 * model_usage 行携带 binding_version_no / alias_version_no / plan_version_no。
 * 离线（内存仓储 + mock provider 链路），覆盖 DefaultModelGateway.account 真实路径。
 */
class UsageCarriesVersionIT {

    private InMemoryModelEntitiesRepository repo;
    private ModelAdminService admin;
    private ModelEntities entities;
    private java.util.List<ModelUsageDO> capturedRows;
    private UsageRecorder usage;
    private DefaultModelGateway gateway;
    private String bindingId;

    @BeforeEach
    void setUp() {
        repo = new InMemoryModelEntitiesRepository();
        entities = new ModelEntities(repo);
        SecretStore store = new MemorySecretStore();
        admin = new ModelAdminService(store, entities,
                mock(com.tioka.framework.security.PrincipalHolder.class),
                ModelTestSupport.disabledVersioning(entities));
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String aliasId = admin.createAlias(new CreateAliasRequest("rerank-m", "rerank", null, null, null));
        bindingId = admin.createModelBinding(new CreateModelBindingRequest(
                aliasId, provider.id(), "rerank-model", "rerank", null));
        String planId = admin.createPlan(new CreateRoutingPlanRequest("r", "FIXED", bindingId)).id();
        admin.publish(planId);
        // 模拟 V151 迁移结果：sys.rerank → routing_plan
        ModelHubSpecs.AliasSeed seed = ModelHubSpecs.sysAliasSeed("RERANK", planId, null);
        entities.putAlias(new com.tioka.model.domain.ModelEntities.ModelAlias(
                seed.id(), seed.name(), null, seed.purpose(), seed.targetType(), seed.targetId(), 2));
        repo.getPlan(planId).orElseThrow().setCurrentVersionNo(7);
        repo.getModelBinding(bindingId).orElseThrow().setCurrentVersionNo(3);

        ModelUsageRepository usageRepo = mock(ModelUsageRepository.class);
        capturedRows = new java.util.ArrayList<>();
        Mockito.doAnswer(inv -> {
            capturedRows.addAll(inv.getArgument(0));
            return null;
        }).when(usageRepo).insertBatch(anyList());
        usage = new UsageRecorder(usageRepo);
        gateway = new DefaultModelGateway(
                new ModelBindingResolver(entities, List.of()),
                new ProviderClient(store, mock(EgressClient.class), true, 8, new ObjectMapper()),
                entities,
                mock(ApplicationEventPublisher.class),
                usage,
                mock(QuotaGuard.class),
                mock(ModelRateLimiter.class),
                mock(ModelBindingCooldownService.class),
                new ModelBudgetGuard(usageRepo),
                2, 2, Duration.ofMillis(1), Duration.ofSeconds(1));
    }

    @Test
    void usageCarriesVersionsViaSystemAlias() {
        gateway.rerank(ctx(), new RerankRequest(null, "查询", List.of("文档一"), 1));
        usage.flush();
        assertEquals(1, capturedRows.size());
        ModelUsageDO row = capturedRows.get(0);
        assertEquals(2, row.getAliasVersionNo(), "sys.rerank 别名版本");
        assertEquals(7, row.getPlanVersionNo(), "路由计划版本");
        assertEquals(3, row.getBindingVersionNo(), "binding 版本");
    }

    @Test
    void forcedBindingUsageHasNoAliasOrPlanVersion() {
        gateway.rerank(ctx(), new RerankRequest(bindingId, "查询", List.of("文档一"), 1));
        usage.flush();
        assertEquals(1, capturedRows.size());
        ModelUsageDO row = capturedRows.get(0);
        assertEquals(3, row.getBindingVersionNo(), "forced 时仍带 binding 版本");
        assertNull(row.getAliasVersionNo());
        assertNull(row.getPlanVersionNo());
    }

    private ModelCallContext ctx() {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, null, null, null, null, ModelPurpose.RERANK);
    }
}
