package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.ModelTestSupport;
import com.tioka.model.ModelTestSupport.InMemoryModelEntitiesRepository;
import com.tioka.model.ModelTestSupport.MemorySecretStore;
import com.tioka.model.api.dto.ProviderDtos.CreateAliasRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.ModelAlias;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MH-08（ADR-041 §1）：别名 CRUD / 用途校验 / 解析；V151 系统别名迁移后（sys.*）
 * 网关按 purpose 解析到指向的路由计划或 binding。离线（内存仓储）。
 */
class ModelAliasIT {

    private final InMemoryModelEntitiesRepository repo = new InMemoryModelEntitiesRepository();
    private final ModelEntities entities = new ModelEntities(repo);
    private final ModelAdminService admin = new ModelAdminService(
            new MemorySecretStore(), entities,
            mock(com.tioka.framework.security.PrincipalHolder.class),
            ModelTestSupport.disabledVersioning(entities));

    @Test
    void aliasCrudAndValidation() {
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String aliasId = admin.createAlias(new CreateAliasRequest("chat.default", "tool",
                ModelPurpose.CHAT.name(), null, null));
        assertTrue(entities.hasAlias(aliasId));

        // 指向尚未创建：校验失败
        BizException missing = assertThrows(BizException.class, () -> admin.createAlias(
                new CreateAliasRequest("chat.other", null, ModelPurpose.CHAT.name(), "binding", "bnd_x")));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());

        String bindingId = admin.createModelBinding(new CreateModelBindingRequest(aliasId, provider.id(), "m1"));
        String ok = admin.createAlias(new CreateAliasRequest("chat.other", null,
                ModelPurpose.CHAT.name(), "binding", bindingId));
        assertTrue(entities.hasAlias(ok));

        // 重名冲突
        BizException dup = assertThrows(BizException.class,
                () -> admin.createAlias(new CreateAliasRequest("chat.other", null, null, null, null)));
        assertEquals(ErrorCode.CONFLICT, dup.getErrorCode());

        // 用途与 binding kind 不匹配
        String embeddingBinding = admin.createModelBinding(new CreateModelBindingRequest(
                aliasId, provider.id(), "emb", "embedding", 32));
        BizException mismatch = assertThrows(BizException.class, () -> admin.createAlias(
                new CreateAliasRequest("chat.bad", null, ModelPurpose.CHAT.name(), "binding", embeddingBinding)));
        assertEquals(ErrorCode.BAD_REQUEST, mismatch.getErrorCode());

        // 指向路由计划
        String planId = admin.createPlan(new CreateRoutingPlanRequest("r", "FIXED", bindingId)).id();
        String planAlias = admin.createAlias(new CreateAliasRequest("chat.plan", null,
                ModelPurpose.CHAT.name(), "routing_plan", planId));
        assertEquals(ModelHubSpecs.TARGET_ROUTING_PLAN, entities.getAlias(planAlias).orElseThrow().targetType());

        assertEquals(3, admin.listAliases().size());
    }

    @Test
    void systemAliasResolutionAfterV151Migration() {
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String aliasId = admin.createAlias(new CreateAliasRequest("m", "tool", null, null, null));
        String bindingId = admin.createModelBinding(new CreateModelBindingRequest(aliasId, provider.id(), "m1"));
        String planId = admin.createPlan(new CreateRoutingPlanRequest("r", "FIXED", bindingId)).id();
        admin.publish(planId);
        // 模拟 V151 迁移结果：sys.chat → routing_plan（带版本）
        seedSysAlias(ModelHubSpecs.sysAliasSeed("CHAT", planId, null), 2);
        repo.getPlan(planId).orElseThrow().setCurrentVersionNo(5);
        repo.getModelBinding(bindingId).orElseThrow().setCurrentVersionNo(3);

        ModelBindingResolver resolver = new ModelBindingResolver(entities, List.of());
        ModelBindingResolver.Resolution r = resolver.resolve(ctx(ModelPurpose.CHAT));
        assertEquals(bindingId, r.bindingId());
        assertEquals("alias_sys_chat", r.aliasId());
        assertEquals(2, r.aliasVersionNo());
        assertEquals(5, r.planVersionNo());
        assertEquals(3, r.binding().currentVersionNo());

        // sys.embedding → 合成 HARD（target binding），无计划版本
        seedSysAlias(ModelHubSpecs.sysAliasSeed("EMBEDDING", null, bindingId), 4);
        ModelBindingResolver.Resolution e = resolver.resolve(ctx(ModelPurpose.EMBEDDING));
        assertEquals(bindingId, e.bindingId());
        assertEquals("alias_sys_embedding", e.aliasId());
        assertEquals(4, e.aliasVersionNo());
        assertNull(e.planVersionNo());

        // 无 sys.decision 别名时回退到已发布方案（迁移未覆盖用途的行为不变）
        ModelBindingResolver.Resolution d = resolver.resolve(ctx(ModelPurpose.DECISION));
        assertEquals(bindingId, d.bindingId());
        assertNull(d.aliasId());
    }

    @Test
    void legacyLogicalModelCreationBecomesAlias() {
        String id = admin.createLogicalModel(new CreateLogicalModelRequest("legacy", "tool"));
        ModelAlias alias = entities.getAlias(id).orElseThrow();
        assertEquals("legacy", alias.name());
        assertNull(alias.purpose());
        assertNull(alias.targetType());
    }

    private void seedSysAlias(ModelHubSpecs.AliasSeed seed, int versionNo) {
        entities.putAlias(new ModelAlias(seed.id(), seed.name(), null,
                seed.purpose(), seed.targetType(), seed.targetId(), versionNo));
    }

    private static ModelCallContext ctx(ModelPurpose purpose) {
        Principal principal = new Principal("u1", "ws1", PrincipalType.USER,
                Set.of("builder"), Set.of("*"), "password");
        return new ModelCallContext(principal, "sess1", null, null, null, purpose);
    }
}
