package com.tioka.model.infrastructure;

import com.tioka.model.infrastructure.persistence.*;

import java.util.List;
import java.util.Optional;

public interface ModelEntitiesRepository {
    void putProvider(ModelProviderDO row);
    Optional<ModelProviderDO> getProvider(String id);
    List<ModelProviderDO> providers();
    void putLogical(ModelLogicalDO row);
    boolean hasLogical(String id);
    void putBinding(ModelBindingDO row);
    Optional<ModelBindingDO> getModelBinding(String id);
    boolean hasBinding(String id);
    List<ModelBindingDO> bindings();
    void putPlan(ModelRoutingPlanDO row);
    Optional<ModelRoutingPlanDO> getPlan(String id);
    /** AST-02：FOR SHARE 锁定路由方案行。 */
    Optional<ModelRoutingPlanDO> lockPlanForShare(String id);
    List<ModelRoutingPlanDO> plans();
    Optional<String> getFreeze(String threadId, String turnId);

    /**
     * INSERT … ON CONFLICT DO NOTHING 后重新读取；返回实际冻结的 bindingId。
     */
    String insertFreezeIfAbsent(String threadId, String turnId, String bindingId, String reason);

    /** 故障接力等场景覆盖已有冻结。 */
    void putFreeze(String threadId, String turnId, String bindingId);

    /** MH-02：更新冻结 binding 并 failover_count + 1；无冻结行时插入。 */
    void recordFailover(String threadId, String turnId, String bindingId, String reason);

    List<ModelTurnFreezeDO> listFreezes(String threadId);
}
