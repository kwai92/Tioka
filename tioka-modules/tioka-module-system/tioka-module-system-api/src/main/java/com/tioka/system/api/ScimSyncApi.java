package com.tioka.system.api;

import java.util.List;
import java.util.Map;

/** SCIM 增量同步（ENG-06：server 只依赖 -api）。 */
public interface ScimSyncApi {
    Map<String, Object> sync(List<Map<String, String>> delta);
}
