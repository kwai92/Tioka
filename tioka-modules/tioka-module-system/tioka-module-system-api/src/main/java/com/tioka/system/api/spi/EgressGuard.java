package com.tioka.system.api.spi;

/** 出网守卫（空气间隙等策略）。 */
public interface EgressGuard {
    void assertAllowed(String url);

    /**
     * AST-02：校验 {@code sandbox.networkAllowlist} 主机格式
     * （精确主机名或 {@code *.example.com} 后缀通配，不含 scheme/路径）。
     * 默认拒绝；生产实现见 {@code DefaultEgressGuard}。
     */
    default boolean isValidHostPattern(String host) {
        return false;
    }
}
