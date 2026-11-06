package com.tioka.model.api;

import java.util.Optional;

/** 跨域只读查询 model_binding（供 Knowledge 等校验嵌入绑定）。 */
public interface BindingQueryApi {

    Optional<BindingInfo> find(String bindingId);

    record BindingInfo(String id, String kind, Integer embeddingDim, boolean enabled) {}
}
