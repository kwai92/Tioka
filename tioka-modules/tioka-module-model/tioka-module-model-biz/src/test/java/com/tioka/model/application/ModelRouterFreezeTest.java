package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.api.dto.ProviderDtos.CreateAliasRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tioka.model.ModelTestSupport.InMemoryModelEntitiesRepository;
import static com.tioka.model.ModelTestSupport.MemorySecretStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * MH-01：轮次冻结单测（替代旧 ModelRouterApi.select 路径）。
 */
class ModelRouterFreezeTest {

    @Test
    void sameTurnKeepsBinding() {
        ModelEntities entities = new ModelEntities(new InMemoryModelEntitiesRepository());
        SecretStore store = new MemorySecretStore();
        ModelAdminService admin = newAdmin(store, entities);
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String lm = admin.createAlias(new CreateAliasRequest("m", "tool", null, null, null));
        String b1 = admin.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "m1"));
        String plan = admin.createPlan(new CreateRoutingPlanRequest("r", "FIXED", b1)).id();
        admin.publish(plan);

        ModelBindingResolver resolver = new ModelBindingResolver(entities, List.of());
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        ModelCallContext ctx = new ModelCallContext(principal, "sess1", "turn1", null, plan, ModelPurpose.CHAT);

        ModelBindingResolver.Resolution s1 = resolver.resolve(ctx);
        ModelBindingResolver.Resolution s2 = resolver.resolve(ctx);
        assertEquals(s1.bindingId(), s2.bindingId());
        assertEquals("frozen", s2.reason());
    }

    @Test
    void difficultyPlanCreateReturns501() {
        ModelEntities entities = new ModelEntities(new InMemoryModelEntitiesRepository());
        SecretStore store = new MemorySecretStore();
        ModelAdminService admin = newAdmin(store, entities);
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String lm = admin.createAlias(new CreateAliasRequest("m", "tool", null, null, null));
        String soft = admin.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "soft"));
        String hard = admin.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "hard"));
        BizException ex = assertThrows(BizException.class,
                () -> admin.createPlan(new CreateRoutingPlanRequest("r", "DIFFICULTY", soft, null, hard)));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, ex.getErrorCode());
    }

    private static ModelAdminService newAdmin(SecretStore store, ModelEntities entities) {
        return new ModelAdminService(store, entities,
                mock(com.tioka.framework.security.PrincipalHolder.class),
                ModelTestSupport.disabledVersioning(entities));
    }
}
