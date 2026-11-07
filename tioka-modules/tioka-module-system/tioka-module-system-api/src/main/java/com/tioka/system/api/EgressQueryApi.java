package com.tioka.system.api;

/** 出网模式查询（ENG-06：server 只依赖 -api）。 */
public interface EgressQueryApi {
    /** {@code true} 表示非 OPEN（ALLOW_LIST / STRICT）。 */
    boolean isRestricted();
}
