package com.tioka.flow.api;

import java.util.Map;

/** 已发布 Flow 内部调用（ENG-06：server 只依赖 -api）。 */
public interface FlowInvokeApi {
    Map<String, Object> invokeInternalApi(String versionId, Map<String, Object> input);
}
