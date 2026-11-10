package com.tioka.connector.api.dataset;

import com.tioka.framework.label.DataLabel;

/**
 * 列规格（DAT-01 / M3-02）：列名、类型、密级与缺省掩码。
 */
public record ColumnSpec(
        String name,
        String type,
        DataLabel label,
        Mask defaultMask
) {
    /** 列掩码（M3-02 掩码表）。 */
    public enum Mask {
        /** 不掩码。 */
        NONE,
        /** 字符串保留前 3 后 4，中间 *；数字保留量级。 */
        PARTIAL,
        /** sha256(tenantSalt + value) 前 16 位。 */
        HASH,
        /** 置为 null。 */
        NULLIFY,
        /** 不返回该列。 */
        DROP
    }

    public ColumnSpec {
        if (label == null) {
            label = DataLabel.INTERNAL;
        }
        if (defaultMask == null) {
            defaultMask = Mask.NONE;
        }
    }
}
