package com.tioka.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.model.application.versioning.ModelConfigVersionService;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.RoutingTelemetryRepository;
import com.tioka.model.infrastructure.persistence.ModelAliasDO;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelCompetenceDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.model.infrastructure.persistence.ModelRouteEventDO;
import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * model-biz 离线测试共用夹具：内存版仓储 / SecretStore / 关闭版本框架的 ModelConfigVersionService。
 */
public final class ModelTestSupport {

    private ModelTestSupport() {
    }

    /** 版本框架未装配（ObjectProvider.getIfAvailable() 为 null）：写入路径 no-op。 */
    @SuppressWarnings("unchecked")
    public static ModelConfigVersionService disabledVersioning(ModelEntities entities) {
        ObjectProvider<ConfigVersionApi> none = (ObjectProvider<ConfigVersionApi>) org.mockito.Mockito.mock(ObjectProvider.class);
        return new ModelConfigVersionService(none, entities, new ObjectMapper());
    }

    public static final class MemorySecretStore implements SecretStore {
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

    public static final class InMemoryModelEntitiesRepository implements ModelEntitiesRepository {
        private final Map<String, ModelProviderDO> providers = new ConcurrentHashMap<>();
        private final Map<String, ModelAliasDO> aliases = new ConcurrentHashMap<>();
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
        public void putAlias(ModelAliasDO row) { aliases.put(row.getId(), row); }

        @Override
        public boolean hasAlias(String id) { return aliases.containsKey(id); }

        @Override
        public Optional<ModelAliasDO> getAlias(String id) { return Optional.ofNullable(aliases.get(id)); }

        @Override
        public List<ModelAliasDO> aliases() { return new ArrayList<>(aliases.values()); }

        @Override
        public Optional<ModelAliasDO> findAliasByName(String name) {
            return aliases.values().stream()
                    .filter(a -> name.equals(a.getName()))
                    .findFirst();
        }

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

    /** MH-06：内存版路由时间线/胜任度仓储（V214 离线测试）。 */
    public static final class InMemoryRoutingTelemetryRepository implements RoutingTelemetryRepository {
        private final List<ModelRouteEventDO> events = new ArrayList<>();
        private final List<ModelCompetenceDO> competences = new ArrayList<>();
        private long seq = 0;

        @Override
        public void insertEvent(ModelRouteEventDO row) {
            if (row.getCreateAt() == null) {
                row.setCreateAt(java.time.LocalDateTime.now().plusNanos(++seq));
            }
            events.add(row);
        }

        @Override
        public Optional<ModelRouteEventDO> latestSelection(String threadId) {
            return events.stream()
                    .filter(e -> threadId.equals(e.getThreadId()))
                    .filter(e -> "SELECT".equals(e.getKind()) || "REUSE".equals(e.getKind()))
                    .max(java.util.Comparator.comparing(ModelRouteEventDO::getCreateAt));
        }

        @Override
        public List<ModelRouteEventDO> eventsByTurn(String turnId) {
            return events.stream().filter(e -> turnId.equals(e.getTurnId()))
                    .sorted(java.util.Comparator.comparing(ModelRouteEventDO::getCreateAt))
                    .toList();
        }

        @Override
        public void insertCompetence(ModelCompetenceDO row) {
            if (row.getCreateAt() == null) {
                row.setCreateAt(java.time.LocalDateTime.now().plusNanos(++seq));
            }
            competences.add(row);
        }

        @Override
        public boolean hasCompetence(String turnId, String bindingId) {
            return competences.stream().anyMatch(c ->
                    turnId.equals(c.getTurnId()) && bindingId.equals(c.getBindingId()));
        }

        @Override
        public Optional<Double> latestCompetenceScore(String bindingId) {
            return competences.stream()
                    .filter(c -> bindingId.equals(c.getBindingId()) && c.getScore() != null)
                    .max(java.util.Comparator.comparing(ModelCompetenceDO::getCreateAt))
                    .map(ModelCompetenceDO::getScore);
        }

        public List<ModelRouteEventDO> allEvents() {
            return List.copyOf(events);
        }

        public List<ModelCompetenceDO> allCompetences() {
            return List.copyOf(competences);
        }
    }
}
