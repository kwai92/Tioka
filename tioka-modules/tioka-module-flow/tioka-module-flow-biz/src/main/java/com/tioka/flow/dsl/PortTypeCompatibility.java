package com.tioka.flow.dsl;

import com.tioka.flow.api.dsl.PortType;

/**
 * ADR-043 §3.4 端口兼容规则。
 */
final class PortTypeCompatibility {

    private PortTypeCompatibility() {
    }

    static boolean compatible(String sourceRaw, String targetRaw) {
        if (sourceRaw == null || targetRaw == null) {
            return true;
        }
        PortType src = PortType.parse(sourceRaw);
        PortType dst = PortType.parse(targetRaw);
        if (src == PortType.ANY || dst == PortType.ANY) {
            return true;
        }
        if (src == dst) {
            if (src == PortType.LIST) {
                String a = PortType.listElement(sourceRaw);
                String b = PortType.listElement(targetRaw);
                if (a == null || b == null) {
                    return true;
                }
                return compatible(a, b);
            }
            return true;
        }
        if ((src == PortType.NUMBER || src == PortType.BOOLEAN) && dst == PortType.TEXT) {
            return true;
        }
        // text → json 禁止
        if (src == PortType.TEXT && dst == PortType.JSON) {
            return false;
        }
        return false;
    }
}
