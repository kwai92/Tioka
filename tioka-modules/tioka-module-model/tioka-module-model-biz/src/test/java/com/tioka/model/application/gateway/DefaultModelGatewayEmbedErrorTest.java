package com.tioka.model.application.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway.EmbeddingRequest;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.DefaultModelGatewayRerankTest.MemorySecretStore;
import com.tioka.model.application.gateway.DefaultModelGatewayRerankTest.MinimalModelEntitiesRepository;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.QuotaGuard;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code DefaultModelGateway} 嵌入/rerank 调用失败时的**异常可诊断性**。
 *
 * <p>背景：G3KnowledgeRecallGateIT 在 CI 偶发失败，turn 因「嵌入调用失败: null」而 FAILED，
 * 但确切异常类型无从得知——因为原实现在 {@code catch (Exception e)} 里直接拼
 * {@code "嵌入调用失败: " + e.getMessage()}，而底层适配器（{@code ProviderClient} 走 JDK HttpClient
 * 发到桩）抛出的异常 message 可能为 null（如 NPE / 某些 IOException），于是异常链信息被丢弃，
 * gate 失败无法归因。本类锁住修复后的行为：message 为 null 时回退到异常类名。
 *
 * <p>复用 {@link DefaultModelGatewayRerankTest} 的内存仓库 fixture（同包可见）；
 * 若该 fixture 被重命名/删除，本类会编译失败并提示，不会静默失效。
 */
class DefaultModelGatewayEmbedErrorTest {

    private DefaultModelGateway gateway;
    private ProviderClient provider;
    private String bindingId;

    @BeforeEach
    void setUp() {
        ModelEntities entities = new ModelEntities(new MinimalModelEntitiesRepository());
        SecretStore store = new MemorySecretStore();
        ModelAdminService admin = new ModelAdminService(store, entities,
                mock(PrincipalHolder.class), ModelTestSupport.disabledVersioning(entities));
        String providerId = admin.createProvider(
                new CreateProviderRequest("p", "mock://openai", "k")).id();
        String lm = admin.createLogicalModel(
                new com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest("emb-m", "embed"));
        bindingId = admin.createModelBinding(new CreateModelBindingRequest(
                lm, providerId, "text-embedding", "embedding", 32));

        ModelUsageRepository usageRepo = mock(ModelUsageRepository.class);
        UsageRecorder usage = new UsageRecorder(usageRepo);
        ModelBudgetGuard budget = new ModelBudgetGuard(usageRepo);
        provider = mock(ProviderClient.class);
        gateway = new DefaultModelGateway(
                new ModelBindingResolver(entities, List.of()),
                provider,
                (com.tioka.model.infrastructure.provider.OcrEngine) (base, key, model, req) ->
                        new com.tioka.model.api.capability.OcrResult(List.of(), null, "mock"),
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

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.tioka.model.application.routing.RoutingPlanExecutor> emptyRouting() {
        return (org.springframework.beans.factory.ObjectProvider<com.tioka.model.application.routing.RoutingPlanExecutor>)
                mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    private ModelCallContext ctx() {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, null, null, null, null, ModelPurpose.EMBEDDING);
    }

    @Test
    void embedPreservesExceptionTypeWhenUnderlyingMessageIsNull() throws Exception {
        // 底层抛 message 为 null 的异常（NPE 等）——这正是 gate 里「嵌入调用失败: null」的来源
        when(provider.embed(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException((String) null));

        BizException ex = assertThrows(BizException.class,
                () -> gateway.embed(ctx(), new EmbeddingRequest(bindingId, List.of("文本"))));

        assertTrue(ex.getMessage().contains("RuntimeException"),
                "message 为 null 时应回退到异常类名以便归因，实际: " + ex.getMessage());
        assertFalse(ex.getMessage().endsWith(": null"),
                "不得再退化为无信息量的 ': null'，实际: " + ex.getMessage());
    }

    @Test
    void embedKeepsUnderlyingMessageWhenPresent() throws Exception {
        // 有 message 时原样保留，防止把 errorMessageOf 误改成「只取类名」而丢失更有用的上游文案
        when(provider.embed(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("上游连接被重置"));

        BizException ex = assertThrows(BizException.class,
                () -> gateway.embed(ctx(), new EmbeddingRequest(bindingId, List.of("文本"))));

        assertTrue(ex.getMessage().contains("上游连接被重置"),
                "有 message 时应原样保留，实际: " + ex.getMessage());
    }
}
