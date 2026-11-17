package com.tioka.framework.foundation;

import com.foundation.starter.core.config.FoundationProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Foundation 模式的启动期契约守卫（F-02 BAS-26 配置项表：{@code mode=foundation} 时中台地址必填）。
 *
 * <p>只在 {@code tioka.foundation.mode=foundation} 时由 {@link FoundationModeConfiguration}
 * 注册。校验的是「底座 starter 契约是否真的在 classpath 上且已装配」——这正是
 * BAS-26 相对 BAS-20 要保证的事：Tioka 已依赖 Foundation 底座，不能只改了配置没接构件。
 * 本地模式不做任何校验，也不要求 Foundation 服务可达（F-00 §5 约定 3）。
 */
public class FoundationModeGuard {

    static final String DEFAULT_BASE_URL = "http://localhost:8080";

    private final TiokaFoundationProperties properties;
    private final ObjectProvider<FoundationProperties> foundationProperties;

    public FoundationModeGuard(TiokaFoundationProperties properties,
                               ObjectProvider<FoundationProperties> foundationProperties) {
        this.properties = properties;
        this.foundationProperties = foundationProperties;
    }

    /** fail-fast：接入模式下缺少底座契约或中台地址即拒绝启动，避免半接线状态。 */
    void validate() {
        if (!properties.isFoundationMode()) {
            return;
        }
        FoundationProperties foundation = foundationProperties.getIfAvailable();
        if (foundation == null) {
            throw new IllegalStateException(
                    "tioka.foundation.mode=foundation but foundation-starter-core is not active; "
                            + "check foundation.enabled and the starter dependency");
        }
        String appCode = foundation.getAppCode();
        if (appCode == null || appCode.isBlank()) {
            throw new IllegalStateException("foundation.app-code is required in foundation mode");
        }
        String baseUrl = foundation.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank() || DEFAULT_BASE_URL.equals(baseUrl.trim())) {
            throw new IllegalStateException(
                    "foundation.base-url must be set explicitly in foundation mode (FOUNDATION_URL)");
        }
    }
}
