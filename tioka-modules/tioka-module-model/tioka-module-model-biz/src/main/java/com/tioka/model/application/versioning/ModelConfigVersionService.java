package com.tioka.model.application.versioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigDraft;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import com.tioka.governance.api.config.ConfigVersionApi.PublishCommand;
import com.tioka.governance.api.config.ConfigVersionApi.RollbackCommand;
import com.tioka.governance.api.config.ConfigVersionApi.SaveDraftCommand;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.ModelAlias;
import com.tioka.model.domain.ModelEntities.ModelBinding;
import com.tioka.model.domain.ModelEntities.Provider;
import com.tioka.model.domain.ModelEntities.RoutingPlan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * MH-08（ADR-041 §2）：四类 Model Hub 对象接入 GOV-12 统一版本框架。
 * <p>版本框架特性开关（{@code tioka.config.version.enabled}）默认关闭：无 ConfigVersionApi bean 时
 * 本服务全部方法返回 empty，写入路径保持既有「直接投影」行为。
 * <p>发布成功后指针回填由 DefaultConfigVersionService → CfgStore.updateTypeTablePointer 完成
 * （复用 ConfigProjectionCache / ConfigCurrentPointerStore 模式），本类不直接触碰 cfg_* 持久化。
 */
@Service
public class ModelConfigVersionService {

    private final ObjectProvider<ConfigVersionApi> versions;
    private final ModelEntities entities;
    private final ObjectMapper mapper;

    public ModelConfigVersionService(ObjectProvider<ConfigVersionApi> versions, ModelEntities entities,
                                     ObjectMapper mapper) {
        this.versions = versions;
        this.entities = entities;
        this.mapper = mapper;
    }

    /** 版本框架是否已装配。 */
    public boolean available() {
        return versions.getIfAvailable() != null;
    }

    /** 保存草稿。 */
    public Optional<ConfigDraft> saveDraft(String objectType, String objectId, JsonNode spec,
                                           Long expectedRevision) {
        ConfigVersionApi api = api(objectType);
        if (api == null || spec == null) {
            return Optional.empty();
        }
        return Optional.of(api.saveDraft(new SaveDraftCommand(
                ref(objectType, objectId), spec, expectedRevision == null ? 0L : expectedRevision)));
    }

    /**
     * 以投影行当前数据发布一个版本（create / update 后调用；V151 迁移的运行时对应物）。
     * 无版本框架、对象不存在时返回 empty。
     */
    public Optional<ConfigVersion> publishCurrent(String objectType, String objectId, String note) {
        ConfigVersionApi api = api(objectType);
        if (api == null) {
            return Optional.empty();
        }
        JsonNode spec = buildSpecFromRow(objectType, objectId);
        if (spec == null) {
            return Optional.empty();
        }
        ObjectRef ref = ref(objectType, objectId);
        long expected = api.getDraft(ref).map(ConfigDraft::revision).orElse(0L);
        api.saveDraft(new SaveDraftCommand(ref, spec, expected));
        return Optional.of(api.publish(new PublishCommand(ref, note)));
    }

    /** 发布已有草稿（显式草稿→发布流）；无草稿时返回 empty。 */
    public Optional<ConfigVersion> publishDraft(String objectType, String objectId, String note) {
        ConfigVersionApi api = api(objectType);
        if (api == null) {
            return Optional.empty();
        }
        ObjectRef ref = ref(objectType, objectId);
        if (api.getDraft(ref).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(api.publish(new PublishCommand(ref, note)));
    }

    /** 回滚到指定版本（指针移动 + 重新投影；不产生新版本号）。 */
    public Optional<ConfigVersion> rollback(String objectType, String objectId, int targetVersionNo,
                                            String reason) {
        ConfigVersionApi api = api(objectType);
        if (api == null) {
            return Optional.empty();
        }
        return Optional.of(api.rollback(new RollbackCommand(ref(objectType, objectId), targetVersionNo, reason)));
    }

    /** 当前生效版本。 */
    public Optional<ConfigVersion> current(String objectType, String objectId) {
        ConfigVersionApi api = api(objectType);
        if (api == null) {
            return Optional.empty();
        }
        return api.getCurrent(ref(objectType, objectId));
    }

    private ConfigVersionApi api(String objectType) {
        if (!ModelHubSpecs.isSupportedType(objectType)) {
            throw new IllegalArgumentException("不支持的版本化对象类型: " + objectType);
        }
        return versions.getIfAvailable();
    }

    private static ObjectRef ref(String objectType, String objectId) {
        return new ObjectRef(objectType, objectId);
    }

    /** spec 结构按 ADR-041 §1「进版本的内容」；行不存在返回 null。 */
    private JsonNode buildSpecFromRow(String objectType, String objectId) {
        return switch (objectType) {
            case ModelHubSpecs.OT_PROVIDER -> entities.getProvider(objectId)
                    .map(this::providerSpec).orElse(null);
            case ModelHubSpecs.OT_BINDING -> entities.getModelBinding(objectId)
                    .map(this::bindingSpec).orElse(null);
            case ModelHubSpecs.OT_ALIAS -> entities.getAlias(objectId)
                    .map(this::aliasSpec).orElse(null);
            case ModelHubSpecs.OT_ROUTING_PLAN -> entities.getPlan(objectId)
                    .map(this::planSpec).orElse(null);
            default -> null;
        };
    }

    private JsonNode providerSpec(Provider p) {
        return ModelHubSpecs.providerSpec(mapper, p.name(), p.baseUrl(), p.apiKeyRef());
    }

    private JsonNode bindingSpec(ModelBinding b) {
        return ModelHubSpecs.bindingSpec(mapper, b.logicalModelId(), b.providerId(), b.modelName(),
                b.kind(), b.contextWindow(), b.embeddingDim(),
                b.inputPriceMicros(), b.outputPriceMicros(), b.rpmLimit(), b.tpmLimit());
    }

    private JsonNode aliasSpec(ModelAlias a) {
        return ModelHubSpecs.aliasSpec(mapper, a.name(), a.capabilityTags(), a.purpose(),
                a.targetType(), a.targetId());
    }

    private JsonNode planSpec(RoutingPlan p) {
        return ModelHubSpecs.planSpec(mapper, p.name(), p.type(), p.primaryBindingId(),
                p.fallbackBindingId(), p.hardBindingId(), p.fallbackChain());
    }
}
