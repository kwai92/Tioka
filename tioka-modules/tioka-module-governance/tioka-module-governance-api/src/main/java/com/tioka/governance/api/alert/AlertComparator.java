package com.tioka.governance.api.alert;

/** 数值条件比较符（GOV-13 / ADR-041 §5.3）。 */
public enum AlertComparator {
    GT, GTE, LT, LTE, EQ, NE;

    /** 判断实测值是否触发。 */
    public boolean test(double actual, double threshold) {
        return switch (this) {
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
            case EQ -> actual == threshold;
            case NE -> actual != threshold;
        };
    }
}
