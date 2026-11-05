package com.tioka.system.api.spi;

/** 出网守卫（空气间隙等策略）。 */
public interface EgressGuard {
    void assertAllowed(String url);
}
