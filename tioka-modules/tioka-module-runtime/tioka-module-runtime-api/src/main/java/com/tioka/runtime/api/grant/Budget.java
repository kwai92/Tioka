package com.tioka.runtime.api.grant;

import java.math.BigDecimal;

/**
 * 授予预算（ADR-039 最小 DTO；实现侧补 remaining / fraction）。
 */
public record Budget(
        BigDecimal amountLimit,
        Integer recordLimit,
        Integer stepLimit
) {
    public static Budget empty() {
        return new Budget(null, null, null);
    }
}
