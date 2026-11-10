package com.tioka.connector.api.dataset;

/**
 * 数据集策略查询 API（DAT-01 / M3-02 PublishValidator）：agent-biz 发布校验用。
 * 实现在 connector-biz（workspace 取当前 Principal）。
 */
public interface DatasetPolicyQueryApi {

    /** 数据集是否存在于当前空间。 */
    boolean exists(String name);

    /**
     * 数据集当前生效策略是否为 ENFORCE（SERVICE 模式发布必须）。
     * 数据集不存在时返回 false。
     */
    boolean hasEnforcePolicy(String name);
}
