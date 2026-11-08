package com.tioka.governance.api.policy;

/**
 * 策略求值端口（M3-05 GOV-07）。实现在 governance-biz，仅本模块可依赖 cedar-java。
 */
public interface PolicyApi {

    PolicyEvalResult evaluate(PolicyEvalRequest request);
}
