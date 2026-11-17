package com.tioka.framework.foundation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tioka 自身运行模式配置（F-02 BAS-26 配置项表）。
 *
 * <p>Tioka 的配置一律走 {@code tioka.*}；{@code foundation.*} 属于底座与中台的配置前缀，
 * 由 {@code foundation-starter-core} 的 {@code FoundationProperties} 绑定。
 */
@ConfigurationProperties(prefix = "tioka.foundation")
public class TiokaFoundationProperties {

    private FoundationMode mode = FoundationMode.LOCAL;

    public FoundationMode getMode() {
        return mode;
    }

    public void setMode(FoundationMode mode) {
        this.mode = mode == null ? FoundationMode.LOCAL : mode;
    }

    public boolean isFoundationMode() {
        return mode == FoundationMode.FOUNDATION;
    }
}
