package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.gateway.ModelBindingResolver;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelDefaultPlanRepository;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.persistence.*;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String lm = admin.createLogicalModel(new CreateLogicalModelRequest("m", "tool"));
        String b1 = admin.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "m1"));
        String plan = admin.createPlan(new CreateRoutingPlanRequest("r", "FIXED", b1)).id();
        admin.publish(plan);

        ModelBindingResolver resolver = new ModelBindingResolver(entities, mock(ModelDefaultPlanRepository.class), List.of());
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
        String lm = admin.createLogicalModel(new CreateLogicalModelRequest("m", "tool"));
        String soft = admin.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "soft"));
        String hard = admin.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "hard"));
        BizException ex = assertThrows(BizException.class,
                () -> admin.createPlan(new CreateRoutingPlanRequest("r", "DIFFICULTY", soft, null, hard)));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, ex.getErrorCode());
    }

    private static ModelAdminService newAdmin(SecretStore store, ModelEntities entities) {
        return new ModelAdminService(
                store, entities, mock(ModelDefaultPlanRepository.class), mock(com.tioka.framework.security.PrincipalHolder.class));
    }

    static final class InMemoryModelEntitiesRepository implements ModelEntitiesRepository {
        private final Map<String, ModelProviderDO> providers = new ConcurrentHashMap<>();
        private final Map<String, ModelLogicalDO> logicals = new ConcurrentHashMap<>();
        private final Map<String, ModelBindingDO> bindings = new ConcurrentHashMap<>();
        private final Map<String, ModelRoutingPlanDO> plans = new ConcurrentHashMap<>();
        private final Map<String, String> freezes = new ConcurrentHashMap<>();
        private final Map<String, String> freezeReasons = new ConcurrentHashMap<>();

        @Override
        public void putProvider(ModelProviderDO row) { providers.put(row.getId(), row); }

        @Override
        public Optional<ModelProviderDO> getProvider(String id) { return Optional.ofNullable(providers.get(id)); }

        @Override
        public List<ModelProviderDO> providers() { return new ArrayList<>(providers.values()); }

        @Override
        public void putLogical(ModelLogicalDO row) { logicals.put(row.getId(), row); }

        @Override
        public boolean hasLogical(String id) { return logicals.containsKey(id); }

        @Override
        public void putBinding(ModelBindingDO row) { bindings.put(row.getId(), row); }

        @Override
        public Optional<ModelBindingDO> getModelBinding(String id) { return Optional.ofNullable(bindings.get(id)); }

        @Override
        public boolean hasBinding(String id) { return bindings.containsKey(id); }

        @Override
        public List<ModelBindingDO> bindings() { return new ArrayList<>(bindings.values()); }

        @Override
        public void putPlan(ModelRoutingPlanDO row) { plans.put(row.getId(), row); }

        @Override
        public Optional<ModelRoutingPlanDO> getPlan(String id) { return Optional.ofNullable(plans.get(id)); }

        @Override
        public Optional<ModelRoutingPlanDO> lockPlanForShare(String id) { return getPlan(id); }

        @Override
        public List<ModelRoutingPlanDO> plans() { return new ArrayList<>(plans.values()); }

        @Override
        public Optional<String> getFreeze(String threadId, String turnId) {
            return Optional.ofNullable(freezes.get(threadId + "|" + turnId));
        }

        @Override
        public String insertFreezeIfAbsent(String threadId, String turnId, String bindingId, String reason) {
            String key = threadId + "|" + turnId;
            freezes.putIfAbsent(key, bindingId);
            freezeReasons.putIfAbsent(key, reason);
            return freezes.get(key);
        }

        @Override
        public void putFreeze(String threadId, String turnId, String bindingId) {
            freezes.put(threadId + "|" + turnId, bindingId);
        }

        @Override
        public void recordFailover(String threadId, String turnId, String bindingId, String reason) {
            String key = threadId + "|" + turnId;
            freezes.put(key, bindingId);
            freezeReasons.put(key, reason);
        }

        @Override
        public List<ModelTurnFreezeDO> listFreezes(String threadId) {
            List<ModelTurnFreezeDO> out = new ArrayList<>();
            for (Map.Entry<String, String> e : freezes.entrySet()) {
                if (!e.getKey().startsWith(threadId + "|")) {
                    continue;
                }
                String turnId = e.getKey().substring(threadId.length() + 1);
                ModelTurnFreezeDO row = new ModelTurnFreezeDO();
                row.setThreadId(threadId);
                row.setTurnId(turnId);
                row.setBindingId(e.getValue());
                row.setReason(freezeReasons.get(e.getKey()));
                row.setFailoverCount(0);
                out.add(row);
            }
            return out;
        }
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
