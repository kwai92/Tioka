package com.tioka.model.api;

import java.util.List;
import java.util.Optional;

/** 跨域只读查询 model_binding（供 Knowledge 等校验嵌入绑定）。 */
public interface ModelBindingQueryApi {

    Optional<ModelBindingInfo> find(String bindingId);

    /** 按 kind 列出（大小写不敏感）；无实现方默认空列表。 */
    default List<ModelBindingInfo> listByKind(String kind) {
        return List.of();
    }

    record ModelBindingInfo(String id, String kind, Integer embeddingDim, boolean enabled, String modelName) {
        /** 兼容旧构造（无 modelName）。 */
        public ModelBindingInfo(String id, String kind, Integer embeddingDim, boolean enabled) {
            this(id, kind, embeddingDim, enabled, null);
        }
    }
}
