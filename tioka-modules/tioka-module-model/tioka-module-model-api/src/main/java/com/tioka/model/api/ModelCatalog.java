package com.tioka.model.api;

import java.util.Optional;

/**
 * 模型目录只读接口（M1 前向兼容约束 F11 / ADR-041 §2「只读执行路径」）。
 * <p>执行侧读取供应商、binding、别名、路由计划只经本接口（带本地缓存），
 * 不得直接读投影表，更不得读 {@code cfg_*}（ArchUnit 规则 cfgInfraOnlyInGovernance）。
 * <p>快照含当前生效版本号（MH-08），供调用记录与统计按版本切片。
 */
public interface ModelCatalog {

    Optional<ProviderSnapshot> currentProvider(String id);

    Optional<BindingSnapshot> currentBinding(String id);

    Optional<AliasSnapshot> currentAlias(String id);

    /** 按稳定名称读取（sys.chat 等系统别名解析入口）。 */
    Optional<AliasSnapshot> aliasByName(String name);

    Optional<PlanSnapshot> currentPlan(String id);

    /** 密钥引用不出目录快照，取用经 ModelGateway/Resolver 既有链路。 */
    record ProviderSnapshot(String id, String name, String baseUrl, Integer currentVersionNo) {}

    record BindingSnapshot(String id, String providerId, String modelName, String kind,
                           Integer embeddingDim, boolean enabled, Integer currentVersionNo) {}

    record AliasSnapshot(String id, String name, String purpose,
                         String targetType, String targetId, Integer currentVersionNo) {}

    record PlanSnapshot(String id, String name, String type, String primaryBindingId,
                        String fallbackChain, boolean published, Integer currentVersionNo) {}
}
