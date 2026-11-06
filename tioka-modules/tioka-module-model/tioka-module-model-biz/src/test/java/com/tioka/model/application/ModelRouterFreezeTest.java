package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.ModelRouterApi;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.persistence.*;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRouterFreezeTest {

    @Test
    void sameSessionKeepsBinding() {
        ModelEntities entities = new ModelEntities(new InMemoryModelEntitiesRepository());
        SecretStore store = new MemorySecretStore();
        ModelAdminService admin = new ModelAdminService(store, entities);
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String lm = admin.createLogicalModel(new CreateLogicalModelRequest("m", "tool"));
        String b1 = admin.createBinding(new CreateBindingRequest(lm, provider.id(), "m1"));
        String plan = admin.createPlan(new CreateRoutingPlanRequest("r", "FIXED", b1)).id();
        admin.publish(plan);
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        ModelRouterService router = new ModelRouterService(ctx, entities);
        ModelRouterApi.ModelSelection s1 = router.select("sess1", plan);
        ModelRouterApi.ModelSelection s2 = router.select("sess1", plan);
        assertEquals(s1.bindingId(), s2.bindingId());
        assertEquals("frozen", s2.reason());
    }

    @Test
    void difficultyPlanCreateReturns501() {
        ModelEntities entities = new ModelEntities(new InMemoryModelEntitiesRepository());
        SecretStore store = new MemorySecretStore();
        ModelAdminService admin = new ModelAdminService(store, entities);
        var provider = admin.createProvider(new CreateProviderRequest("p", "mock://openai", "k"));
        String lm = admin.createLogicalModel(new CreateLogicalModelRequest("m", "tool"));
        String soft = admin.createBinding(new CreateBindingRequest(lm, provider.id(), "soft"));
        String hard = admin.createBinding(new CreateBindingRequest(lm, provider.id(), "hard"));
        BizException ex = assertThrows(BizException.class,
                () -> admin.createPlan(new CreateRoutingPlanRequest("r", "DIFFICULTY", soft, null, hard)));
        assertEquals(ErrorCode.NOT_IMPLEMENTED, ex.getErrorCode());
    }

    static final class InMemoryModelEntitiesRepository implements ModelEntitiesRepository {
        private final Map<String, ModelProviderDO> providers = new ConcurrentHashMap<>();
        private final Map<String, ModelLogicalDO> logicals = new ConcurrentHashMap<>();
        private final Map<String, ModelBindingDO> bindings = new ConcurrentHashMap<>();
        private final Map<String, ModelRoutingPlanDO> plans = new ConcurrentHashMap<>();
        private final Map<String, String> freezes = new ConcurrentHashMap<>();

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
        public Optional<ModelBindingDO> getBinding(String id) { return Optional.ofNullable(bindings.get(id)); }

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
        public Optional<String> getFreeze(String sessionId, String turnId) {
            return Optional.ofNullable(freezes.get(sessionId + "|" + turnId));
        }

        @Override
        public void putFreeze(String sessionId, String turnId, String bindingId) {
            freezes.put(sessionId + "|" + turnId, bindingId);
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
