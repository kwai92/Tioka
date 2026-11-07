package com.tioka.model.application.gateway;

import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.domain.ModelEntities;

/**
 * 选模过滤链扩展点（M1-00 F8）：M1 放冷却，M3 放数据标签。默认实现恒放行。
 */
@FunctionalInterface
public interface ModelBindingFilter {

    /** @return true 表示该 binding 可用 */
    boolean test(ModelCallContext ctx, ModelEntities.ModelBinding binding);
}
