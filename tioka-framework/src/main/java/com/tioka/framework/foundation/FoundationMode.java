package com.tioka.framework.foundation;

import java.util.Locale;

/**
 * Tioka 接入 Foundation 的运行模式（F-02 BAS-26 配置项、F-11 BAS-20 接线）。
 *
 * <p>{@link #LOCAL} 是默认值：私有化单 Tioka 部署，自建身份与权限，不连中台服务。
 * {@link #FOUNDATION} 表示作为「接入应用」接入 Foundation 中台；该模式的实际接线
 * （授权快照、starter、离职联动）由 BAS-20 完成，本类只提供模式判定与启动期守卫。
 */
public enum FoundationMode {

    /** 本地模式：不依赖 Foundation 中台服务（默认）。 */
    LOCAL,

    /** 接入模式：经 starter 与契约接入 Foundation 中台。 */
    FOUNDATION;

    /** 宽松解析配置值；空值按 {@link #LOCAL} 处理，非法值直接拒绝而不是静默降级。 */
    public static FoundationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "local" -> LOCAL;
            case "foundation" -> FOUNDATION;
            default -> throw new IllegalArgumentException(
                    "Unsupported tioka.foundation.mode: " + raw + " (expected local|foundation)");
        };
    }
}
